package com.yyds.biz.service.sys;

import com.yyds.biz.config.base.BaseConfiguration;
import com.yyds.biz.constant.SysConstant;
import com.yyds.biz.entity.base.BaseResultEntity;
import com.yyds.biz.entity.base.BaseResultEnum;
import com.yyds.biz.entity.base.PageParam;
import com.yyds.biz.entity.sys.param.FindUserPageParam;
import com.yyds.biz.entity.sys.param.LoginParam;
import com.yyds.biz.entity.sys.param.SaveOrUpdateUserParam;
import com.yyds.biz.entity.sys.po.*;
import com.yyds.biz.entity.sys.vo.SysAuthNodeVO;
import com.yyds.biz.entity.sys.vo.SysUserListVO;
import com.yyds.biz.repository.primarydb.sys.SysUserPrimarydbRepository;
import com.yyds.biz.repository.primaryredis.sys.SysCommonPrimaryRedisRepository;
import com.yyds.biz.repository.primaryredis.sys.SysUserPrimaryRedisRepository;
import com.yyds.biz.repository.secondarydb.sys.SysOrganSecondarydbRepository;
import com.yyds.biz.repository.secondarydb.sys.SysRoleSecondarydbRepository;
import com.yyds.biz.repository.secondarydb.sys.SysUserSecondarydbRepository;
import com.yyds.biz.tool.PlatformHelper;
import com.yyds.biz.util.crypt.CryptUtil;
import com.yyds.biz.util.crypt.SignUtil;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class SysUserService {

    @Autowired
    private SysUserPrimarydbRepository sysUserPrimarydbRepository;
    @Autowired
    private SysUserSecondarydbRepository sysUserSecondarydbRepository;
    @Autowired
    private SysRoleSecondarydbRepository sysRoleSecondarydbRepository;
    @Autowired
    private SysOrganSecondarydbRepository sysOrganSecondarydbRepository;
    @Autowired
    private SysCommonPrimaryRedisRepository sysCommonPrimaryRedisRepository;
    @Autowired
    private SysUserPrimaryRedisRepository sysUserPrimaryRedisRepository;
    @Autowired
    private SysAuthService sysAuthService;
    @Autowired
    private BaseConfiguration baseConfiguration;

    public BaseResultEntity login(LoginParam loginParam){
        String privateKey=sysCommonPrimaryRedisRepository.getRsaKey(loginParam.getValidateKeyName());
        if(privateKey==null)
            return BaseResultEntity.failure(BaseResultEnum.VALIDATE_KEY_INVALIDATION);
        SysUser sysUser=sysUserSecondarydbRepository.selectUserByUserAccount(loginParam.getUserAccount());
        if(sysUser==null||sysUser.getUserId()==null)
            return BaseResultEntity.failure(BaseResultEnum.ACCOUNT_NOT_FOUND);

        String userPassword;
        try {
            userPassword=CryptUtil.decryptRsaWithPrivateKey(loginParam.getUserPassword(),privateKey);
        } catch (Exception e) {
            return BaseResultEntity.failure(BaseResultEnum.FAILURE,"解密失败");
        } 
        StringBuffer sb=new StringBuffer().append(baseConfiguration.getDefaultPasswordVector()).append(userPassword);
        String signPassword=SignUtil.getMD5ValueLowerCaseByDefaultEncode(sb.toString());
        if(!signPassword.equals(sysUser.getUserPassword()))
            return BaseResultEntity.failure(BaseResultEnum.PASSWORD_NOT_CORRECT);

        Set<Long> roleIdSet=Stream.of(sysUser.getRoleIdList().split(",")).filter(item->!item.equals(""))
                .map(item->(Long.parseLong(item))).collect(Collectors.toSet());
        Set<Long> authIdList=sysRoleSecondarydbRepository.selectRaByBatchRoleId(roleIdSet);
//        List<SysAuthNodeVO> roleAuthRootList=sysAuthService.getSysAuthTree(authIdList);
        List<SysAuthNodeVO> roleAuthRootList=sysAuthService.getSysAuthForBfs();
        List<SysAuthNodeVO> grantAuthRootList=roleAuthRootList.stream().filter(item->authIdList.contains(item.getAuthId()))
                .map(item->{item.setIsGrant(1); return item;}).collect(Collectors.toList());


        Set<Long> organIdSet=new HashSet<>();
        organIdSet.addAll(Stream.of(sysUser.getOrganIdList().split(",")).filter(item->!item.equals(""))
                .map(item->(Long.parseLong(item))).collect(Collectors.toSet()));
        organIdSet.addAll(Stream.of(sysUser.getROrganIdList().split(",")).filter(item->!item.equals(""))
                .map(item->(Long.parseLong(item))).collect(Collectors.toSet()));

        List<SysRole> roleList=roleIdSet.size()==0?new ArrayList<>():sysRoleSecondarydbRepository.selectSysRoleByBatchRoleId(roleIdSet);
        List<SysOrgan> organList=organIdSet.size()==0?new ArrayList<>():sysOrganSecondarydbRepository.selectSysOrganByBatchOrganId(organIdSet);
        Map<Long,String> roleMap=roleList.stream().collect(Collectors.toMap(SysRole::getRoleId,SysRole::getRoleName,(x,y)->x));
        Map<Long,String> organMap=organList.stream().collect(Collectors.toMap(SysOrgan::getOrganId,SysOrgan::getOrganName,(x,y)->x));

        SysUserListVO sysUserListVO=new SysUserListVO();
        BeanUtils.copyProperties(sysUser,sysUserListVO);
        String authIdListStr=authIdList.stream().map(String::valueOf).collect(Collectors.joining(","));
        sysUserListVO.setAuthIdList(authIdListStr);

        String roleIdListDesc = formIdDesc(roleMap, sysUserListVO.getRoleIdList());
        sysUserListVO.setRoleIdListDesc(roleIdListDesc);
        String organIdListDesc = formIdDesc(organMap, sysUserListVO.getOrganIdList());
        sysUserListVO.setOrganIdListDesc(organIdListDesc);
        String rOrganIdListDesc = formIdDesc(organMap, sysUserListVO.getROrganIdList());
        sysUserListVO.setROrganIdListDesc(rOrganIdListDesc);

        Date date=new Date();

        String token=PlatformHelper.generateOwnToken(SysConstant.SYS_USER_TOKEN_PREFIX,sysUser.getUserId(),date);

        sysUserPrimaryRedisRepository.updateUserLoginStatus(token,sysUserListVO);

        Map map=new HashMap<>();
        map.put("sysUser",sysUserListVO);
//        map.put("roleAuthRootList",roleAuthRootList);
        map.put("grantAuthRootList",grantAuthRootList);
        map.put("token",token);
        return BaseResultEntity.success(map);
    }

    public BaseResultEntity logout(String token,Long userId){
        if(token!=null&&token.equals("")&&userId==null) {
            sysUserPrimaryRedisRepository.deleteUserLoginStatus(token, userId);
        }
        return BaseResultEntity.success();
    }

    public BaseResultEntity saveOrUpdateUser(SaveOrUpdateUserParam saveOrUpdateUserParam){
        Long userId=saveOrUpdateUserParam.getUserId();
        SysUser sysUser;
        if(userId==null){
            if(saveOrUpdateUserParam.getUserAccount()==null||saveOrUpdateUserParam.getUserAccount().trim().equals(""))
                return BaseResultEntity.failure(BaseResultEnum.LACK_OF_PARAM,"userAccount");
            if(saveOrUpdateUserParam.getUserName()==null||saveOrUpdateUserParam.getUserName().trim().equals(""))
                return BaseResultEntity.failure(BaseResultEnum.LACK_OF_PARAM,"userName");
            if(saveOrUpdateUserParam.getIsForbid()==null)
                saveOrUpdateUserParam.setIsForbid(0);
            if(sysUserSecondarydbRepository.isExistUserAccount(saveOrUpdateUserParam.getUserAccount()))
                return BaseResultEntity.failure(BaseResultEnum.NON_REPEATABLE,"账户名称");

            sysUser = new SysUser();
            BeanUtils.copyProperties(saveOrUpdateUserParam,sysUser);
            StringBuffer sb=new StringBuffer().append(baseConfiguration.getDefaultPasswordVector()).append(baseConfiguration.getDefaultPassword());
            sysUser.setUserPassword(SignUtil.getMD5ValueLowerCaseByDefaultEncode(sb.toString()));
            sysUser.setRoleIdList("");
            sysUser.setOrganIdList("");
            sysUser.setROrganIdList("");
            sysUser.setIsForbid(saveOrUpdateUserParam.getIsForbid());
            sysUser.setIsEditable(1);
            sysUser.setIsDel(0);
            sysUserPrimarydbRepository.insertSysUser(sysUser);
            userId=sysUser.getUserId();
        }else{
            sysUser=sysUserSecondarydbRepository.selectSysUserByUserId(userId);
            if(sysUser==null||sysUser.getUserId()==null)
                return BaseResultEntity.failure(BaseResultEnum.CAN_NOT_ALTER,"不存在该数据");
            if(sysUser.getIsEditable().equals(0))
                return BaseResultEntity.failure(BaseResultEnum.CAN_NOT_ALTER,"该记录是不可编辑状态");
            if((saveOrUpdateUserParam.getUserName()!=null&&!saveOrUpdateUserParam.getUserName().trim().equals(""))
                ||(saveOrUpdateUserParam.getIsForbid()!=null)){
                Map paramMap=new HashMap(){
                    {
                        put("userId",saveOrUpdateUserParam.getUserId());
                        put("userName",saveOrUpdateUserParam.getUserName());
                        put("isForbid",saveOrUpdateUserParam.getIsForbid());
                    }
                };
                sysUserPrimarydbRepository.updateSysUserExplicit(paramMap);
                sysUserPrimaryRedisRepository.deleteUserLoginStatus(saveOrUpdateUserParam.getUserId());
            }
        }

        boolean roleFlag=false;
        if(saveOrUpdateUserParam.getRoleIdList()!=null&&saveOrUpdateUserParam.getRoleIdList().length!=0){
            roleFlag=true;
//            sysUserPrimarydbRepository.deleteSysUrBatch(saveOrUpdateUserParam.getRoleIdList(),userId);
            sysUserPrimarydbRepository.deleteSysUrBatch(userId);
            List<SysUr> urList=new ArrayList<>();
            for(Long roleId:saveOrUpdateUserParam.getRoleIdList()){
                SysUr sysUr=new SysUr();
                sysUr.setRoleId(roleId);
                sysUr.setUserId(userId);
                sysUr.setIsDel(0);
                urList.add(sysUr);
            }
            sysUserPrimarydbRepository.insertSysUrBatch(urList);
        }

        boolean organFlag=false;
        if(saveOrUpdateUserParam.getOrganIdList()!=null&&saveOrUpdateUserParam.getOrganIdList().length!=0){
            organFlag=true;
            sysUserPrimarydbRepository.deleteSysUoBatch(saveOrUpdateUserParam.getOrganIdList(),userId);
            List<SysUo> uoList=new ArrayList<>();
            for(Long roleId:saveOrUpdateUserParam.getRoleIdList()){
                SysUo sysUo=new SysUo();
                sysUo.setOrganId(roleId);
                sysUo.setUserId(userId);
                sysUo.setIsDel(0);
                uoList.add(sysUo);
            }
            sysUserPrimarydbRepository.insertSysUoBatch(uoList);
        }

        String roleIdListStr=roleFlag?Stream.of(saveOrUpdateUserParam.getRoleIdList()).map(item->(String.valueOf(item))).collect(Collectors.joining(",")):"";
        String organIdListStr=organFlag?Stream.of(saveOrUpdateUserParam.getOrganIdList()).map(item->(String.valueOf(item))).collect(Collectors.joining(",")):"";
        String rOrganIdListStr=organFlag?Stream.of(saveOrUpdateUserParam.getROrganIdList()).map(item->(String.valueOf(item))).collect(Collectors.joining(",")):"";
        if(!roleIdListStr.equals("")||!organIdListStr.equals("")||!rOrganIdListStr.equals("")){
            Map paramMap=new HashMap(){
                {
                    put("userId",sysUser.getUserId());
                    put("roleIdList",roleIdListStr);
                    put("organIdList",organIdListStr);
                    put("rOrganIdList",rOrganIdListStr);
                }
            };
            sysUserPrimarydbRepository.updateSysUserExplicit(paramMap);
            sysUserPrimaryRedisRepository.deleteUserLoginStatus(sysUser.getUserId());
        }

        sysUser.setUserPassword("");
        Map map=new HashMap<>();
        map.put("sysUser",sysUser);
        return BaseResultEntity.success(map);
    }

    public BaseResultEntity deleteSysUser(Long userId){
        SysUser sysUser=sysUserSecondarydbRepository.selectSysUserByUserId(userId);
        if(sysUser==null||sysUser.getUserId()==null)
            return BaseResultEntity.failure(BaseResultEnum.CAN_NOT_DELETE,"不存在该数据");
        if(sysUser.getIsEditable().equals(0))
            return BaseResultEntity.failure(BaseResultEnum.CAN_NOT_DELETE,"该记录是不可编辑状态");
        sysUserPrimarydbRepository.updateSysUserDelStatus(userId);
        sysUserPrimaryRedisRepository.deleteUserLoginStatus(userId);
        return BaseResultEntity.success();
    }

    public BaseResultEntity findUserPage(FindUserPageParam findUserPageParam,Integer pageNum,Integer pageSize){
        PageParam pageParam=new PageParam(pageNum,pageSize);
        Map paramMap=new HashMap(){
            {
                put("userName",findUserPageParam.getUserName());
                put("organId",findUserPageParam.getOrganId());
                put("rOrganId",findUserPageParam.getROrganId());
                put("roleId",findUserPageParam.getRoleId());
                put("pageIndex",pageParam.getPageIndex());
                put("pageSize",pageParam.getPageSize()+1);
            }
        };
        List<SysUserListVO> sysUserList =sysUserSecondarydbRepository.selectSysUserListByParam(paramMap);
        Long count=sysUserSecondarydbRepository.selectSysUserListCountByParam(paramMap);
        Set<Long> roleIdSet=new HashSet<>();
        Set<Long> organIdSet=new HashSet<>();
        for(SysUserListVO sysUserListVO:sysUserList){
            roleIdSet.addAll(Stream.of(sysUserListVO.getRoleIdList().split(",")).filter(item->!item.equals(""))
                    .map(item->(Long.parseLong(item))).collect(Collectors.toSet()));
            organIdSet.addAll(Stream.of(sysUserListVO.getOrganIdList().split(",")).filter(item->!item.equals(""))
                    .map(item->(Long.parseLong(item))).collect(Collectors.toSet()));
            organIdSet.addAll(Stream.of(sysUserListVO.getROrganIdList().split(",")).filter(item->!item.equals(""))
                    .map(item->(Long.parseLong(item))).collect(Collectors.toSet()));
        }
        List<SysRole> roleList=roleIdSet.size()==0?new ArrayList<>():sysRoleSecondarydbRepository.selectSysRoleByBatchRoleId(roleIdSet);
        List<SysOrgan> organList=organIdSet.size()==0?new ArrayList<>():sysOrganSecondarydbRepository.selectSysOrganByBatchOrganId(organIdSet);
        Map<Long,String> roleMap=roleList.stream().collect(Collectors.toMap(SysRole::getRoleId,SysRole::getRoleName,(x,y)->x));
        Map<Long,String> organMap=organList.stream().collect(Collectors.toMap(SysOrgan::getOrganId,SysOrgan::getOrganName,(x,y)->x));
        for(SysUserListVO sysUserListVO:sysUserList){
            String roleIdListDesc = formIdDesc(roleMap, sysUserListVO.getRoleIdList());
            sysUserListVO.setRoleIdListDesc(roleIdListDesc);
            String organIdListDesc = formIdDesc(organMap, sysUserListVO.getOrganIdList());
            sysUserListVO.setOrganIdListDesc(organIdListDesc);
            String rOrganIdListDesc = formIdDesc(organMap, sysUserListVO.getROrganIdList());
            sysUserListVO.setROrganIdListDesc(rOrganIdListDesc);
        }
        pageParam.isLoadMore(sysUserList);
        pageParam.initItemTotalCount(count);
        Map map=new HashMap<>();
        map.put("sysUserList",sysUserList);
        map.put("pageParam",pageParam);
        return BaseResultEntity.success(map);
    }

    private String formIdDesc(Map<Long, String> roleMap, String idListStr) {
        StringBuilder sb=new StringBuilder();
        if(idListStr!=null&&!idListStr.trim().equals("")) {
            String[] idStrArray = idListStr.split(",");
            for (String idStr:idStrArray){
                if(idStr!=null&&!idStr.trim().equals("")){
                    Long currentId=Long.parseLong(idStr);
                    String currentName= roleMap.get(currentId);
                    if(currentName!=null)
                        sb.append(currentName).append(",");
                }
            }
        }
        if(sb.length()>0) sb.setLength(sb.length()-1);
        return sb.toString();
    }

    public BaseResultEntity initPassword(Long userId){
//        StringBuffer sb=new StringBuffer().append(baseConfiguration.getDefaultPasswordVector()).append(baseConfiguration.getDefaultPassword());
//        String userPassword=SignUtil.getMD5ValueLowerCaseByDefaultEncode(sb.toString());
//        Map paramMap=new HashMap(){
//            {
//                put("userId",userId);
//                put("userPassword",userPassword);
//            }
//        };
//        sysUserPrimarydbRepository.updateSysUserExplicit(paramMap);
        updatePassword(userId,baseConfiguration.getDefaultPassword());
        return BaseResultEntity.success();
    }

    public Map<Long, SysUser> getSysUserMap(Set<Long> userIdSet){
        if(userIdSet==null||userIdSet.size()==0){
            return new HashMap<>();
        }
        List<SysUser> sysUsers = this.sysUserSecondarydbRepository.selectSysUserByUserIdSet(userIdSet);
        if (sysUsers.size()>0){
            return sysUsers.stream().collect(Collectors.toMap(SysUser::getUserId, Function.identity(),(key1,key2)->key2));
        }
        return new HashMap<>();
    }

    public SysUser getSysUserById(Long userid){
        if (userid == null || userid.compareTo(0L) == 0){
            return null;
        }
        return this.sysUserSecondarydbRepository.selectSysUserByUserId(userid);
    }

    public BaseResultEntity updatePassword(Long userId, String password,String validateKeyName) {
        String privateKey=sysCommonPrimaryRedisRepository.getRsaKey(validateKeyName);
        if(privateKey==null)
            return BaseResultEntity.failure(BaseResultEnum.VALIDATE_KEY_INVALIDATION);
        String oldAndNewPassword;
        try {
            oldAndNewPassword=CryptUtil.decryptRsaWithPrivateKey(password,privateKey);
        } catch (Exception e) {
            return BaseResultEntity.failure(BaseResultEnum.FAILURE,"解密失败");
        }
        String[] passwordArray = oldAndNewPassword.split(",");
        if (passwordArray.length!=2)
            return BaseResultEntity.failure(BaseResultEnum.PARAM_INVALIDATION,"password");
        SysUser sysUser=sysUserSecondarydbRepository.selectSysUserContainPassByUserId(userId);
        if(sysUser==null||sysUser.getUserId()==null)
            return BaseResultEntity.failure(BaseResultEnum.ACCOUNT_NOT_FOUND);
        String oldPassword = passwordArray[0];
        StringBuffer sb=new StringBuffer().append(baseConfiguration.getDefaultPasswordVector()).append(oldPassword);
        String signPassword=SignUtil.getMD5ValueLowerCaseByDefaultEncode(sb.toString());
        if(!signPassword.equals(sysUser.getUserPassword()))
            return BaseResultEntity.failure(BaseResultEnum.OLD_PASSWORD_NOT_CORRECT);
        String newPassword = passwordArray[1];
        updatePassword(userId,newPassword);
        return BaseResultEntity.success();
    }

    public void updatePassword(Long userId, String password){
        StringBuffer sb=new StringBuffer().append(baseConfiguration.getDefaultPasswordVector()).append(password);
        String userPassword=SignUtil.getMD5ValueLowerCaseByDefaultEncode(sb.toString());
        Map paramMap=new HashMap(){
            {
                put("userId",userId);
                put("userPassword",userPassword);
            }
        };
        sysUserPrimarydbRepository.updateSysUserExplicit(paramMap);
    }
}