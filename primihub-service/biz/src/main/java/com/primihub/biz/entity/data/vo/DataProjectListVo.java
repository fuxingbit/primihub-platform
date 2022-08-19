package com.primihub.biz.entity.data.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class DataProjectListVo {

    /**
     * 本地真实ID
     */
    private Long id;
    /**
     * 项目id
     */
    private String projectId;
    /**
     * 项目名称
     */
    private String projectName;
    /**
     * 项目描述
     */
    private String projectDesc;

    /**
     * 创建者名称
     */
    private String userName;

    /**
     * 创建机构id
     */
    private String organId;

    /**
     * 机构名称
     */
    private String createdOrganName;

    /**
     * 资源数
     */
    private Integer resourceNum;

    private Integer modelNum = 0;
    private Integer modelAssembleNum = 0;
    private Integer modelRunNum = 0;
    private Integer modelSuccessNum = 0;

    /**
     * 协作方机构名称 保存三个
     */
    private String providerOrganNames;

    /**
     * 项目状态 0审核中 1可用 2关闭
     */
    private Integer status;
    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss",timezone="GMT+8")
    private Date createDate;


}
