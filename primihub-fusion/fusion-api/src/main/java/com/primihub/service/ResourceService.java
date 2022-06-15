package com.primihub.service;

import com.primihub.convert.DataResourceConvert;
import com.primihub.entity.base.BaseResultEntity;
import com.primihub.entity.base.BaseResultEnum;
import com.primihub.entity.base.PageDataEntity;
import com.primihub.entity.copy.dto.CopyResourceDto;
import com.primihub.entity.fusion.po.FusionOrgan;
import com.primihub.entity.resource.param.PageParam;
import com.primihub.entity.resource.param.ResourceParam;
import com.primihub.entity.resource.po.FusionPublicRo;
import com.primihub.entity.resource.po.FusionResource;
import com.primihub.repository.FusionRepository;
import com.primihub.repository.FusionResourceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ResourceService {

    @Autowired
    private FusionResourceRepository resourceRepository;
    @Autowired
    private FusionRepository fusionRepository;


    public BaseResultEntity getResourceList(ResourceParam param) {
        List<FusionResource> fusionResources = resourceRepository.selectFusionResource(param);
        if (fusionResources.isEmpty())
            return BaseResultEntity.success(new PageDataEntity(0,param.getPageSize(),param.getPageNo(),new ArrayList()));
        Integer count = resourceRepository.selectFusionResourceCount(param);
        Set<String> organIds = fusionResources.stream().map(FusionResource::getOrganId).collect(Collectors.toSet());
        Map<String, String> organNameMap = fusionRepository.selectFusionOrganByGlobalIds(organIds).stream().collect(Collectors.toMap(FusionOrgan::getGlobalId, FusionOrgan::getGlobalName));
        return BaseResultEntity.success(new PageDataEntity(count,param.getPageSize(),param.getPageNo(),fusionResources.stream().map(re-> DataResourceConvert.fusionResourcePoConvertVo(re,organNameMap.get(re.getOrganId()))).collect(Collectors.toList())));
    }

    public BaseResultEntity getDataResource(String resourceId) {
        FusionResource fusionResource = resourceRepository.selectFusionResourceByResourceId(resourceId);
        if (fusionResource==null)
            return BaseResultEntity.success();
        FusionOrgan fusionOrgan = fusionRepository.getFusionOrganByGlobalId(fusionResource.getOrganId());
        return BaseResultEntity.success(DataResourceConvert.fusionResourcePoConvertVo(fusionResource,fusionOrgan==null?"":fusionOrgan.getGlobalName()));
    }


    public BaseResultEntity getResourceTagList() {
        return BaseResultEntity.success(resourceRepository.selectFusionResourceTag());
    }

    public BaseResultEntity batchSaveResource(String globalId,List<CopyResourceDto> copyResourceDtoList){
        if (StringUtils.isEmpty(globalId)){
            return BaseResultEntity.failure(BaseResultEnum.LACK_OF_PARAM,"globalId");
        }
        String organShortCode = getOrganShortCode(globalId);
        List<CopyResourceDto> filterDtoList = copyResourceDtoList.stream().filter(dto -> dto.getResourceId() == null && StringUtils.isEmpty(dto.getResourceId()) && !dto.getResourceId().substring(0, 11).equals(organShortCode)).collect(Collectors.toList());
        if (filterDtoList!=null && filterDtoList.size()>0){
            StringBuilder sb = new StringBuilder("执行复制任务失败:").append("【条件检验未通过】\n");
            for (CopyResourceDto copyResourceDto : filterDtoList) {
                sb.append("resourceId:").append(copyResourceDto.getResourceId()).append("-").
                append("resourceName:").append(copyResourceDto.getResourceName()).append("-").
                append("organId:").append(copyResourceDto.getOrganId()).append("\n");
            }
            return BaseResultEntity.failure(BaseResultEnum.DATA_EXECUTE_TASK_FAIL,sb.toString());
        }
        log.info(globalId);
        Set<String> resourceIds = copyResourceDtoList.stream().map(CopyResourceDto::getResourceId).collect(Collectors.toSet());
        List<FusionResource> fusionResources = resourceRepository.selectFusionResourceById(resourceIds);
        Set<String> existenceResourceIds = fusionResources.stream().map(FusionResource::getResourceId).collect(Collectors.toSet());
        Map<String, FusionResource> fusionResourcesMap = fusionResources.stream().collect(Collectors.toMap(FusionResource::getResourceId, Function.identity()));
        Set<String> saveTags = new HashSet<>();
        Set<String> existenceTags = new HashSet<>();
        List<FusionPublicRo> roList = new ArrayList<>();
        for (CopyResourceDto copyResourceDto : copyResourceDtoList) {
            FusionResource fusionResource = DataResourceConvert.CopyResourceDtoConvertPo(copyResourceDto);
            if (existenceResourceIds.contains(fusionResource.getResourceId())){
                FusionResource fr = fusionResourcesMap.get(fusionResource.getResourceId());
                fusionResource.setId(fr.getId());
                resourceRepository.updateFusionResource(fusionResource);
                existenceTags.addAll(Arrays.asList(fr.getResourceTag().split(",")));
            }else {
                resourceRepository.saveFusionResource(fusionResource);
            }
            if (fusionResource.getResourceAuthType()==3){
                resourceRepository.deleteFusionPublicRoByResourceId(fusionResource.getId());
                roList.add(new FusionPublicRo(fusionResource.getId(),fusionResource.getOrganId()));

                copyResourceDto.getAuthOrganList();
            }
            if (!StringUtils.isEmpty(copyResourceDto.getResourceTag()))
                saveTags.addAll(Arrays.asList(copyResourceDto.getResourceTag().split(",")));
        }
        saveTags.removeAll(existenceTags);
        if (!saveTags.isEmpty())
            resourceRepository.saveBatchResourceTag(saveTags);
        if (!roList.isEmpty())
            resourceRepository.saveBatchFusionPublicRo(roList);
        return BaseResultEntity.success();
    }


    public String getOrganShortCode(String globalId){
        return globalId.substring(24,36);
    }
}