package com.motcs.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * url文件上传请求DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadRequest {

    /**
     * 文档URL
     */
    private String url;

    /**
     * 文档标题
     */
    private String title;

    /**
     * 文档描述
     */
    private String description;

    /**
     * 文档业务编码，用于标识同一份文件。
     * 重复上传相同 docCode 时会先删除旧数据再插入新数据（更新语义）。
     */
    private String docCode;

    /**
     * 租户编码，用于多租户数据隔离
     */
    private String tenantCode;

    /**
     * 系统类型，用于按系统区分数据
     */
    private String systemType;

    /**
     * 上传者用户编码
     */
    private String userId;

}
