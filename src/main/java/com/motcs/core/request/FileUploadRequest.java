package com.motcs.core.request;

import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(description = "文档URL", example = "文档URL")
    private String url;

    @Schema(description = "文档标题", example = "文件名称")
    private String title;

    @Schema(description = "文档描述", example = "这是...文档描述")
    private String description;

    @Schema(description = "文档业务编码，用于标识同一份文件。", example = "重复上传相同 docCode 时会先删除旧数据再插入新数据（更新语义）。")
    private String docCode;

    @Schema(description = "租户编码，用于多租户数据隔离", example = "410725")
    private String tenantCode;

    @Schema(description = "系统类型，用于按系统区分数据", example = "other")
    private String systemType;

    @Schema(description = "上传者用户编码", example = "user001")
    private String userId;

}
