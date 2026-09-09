package com.motcs.core.document;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档上传请求DTO
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
@Schema(description = "文档上传请求参数")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentUploadRequest {

    @Schema(description = "上传的文件", requiredMode = Schema.RequiredMode.REQUIRED)
    private MultipartFile file;

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "文档标题")
    private String title;

    @Schema(description = "文档描述")
    private String description;

    @Schema(description = "文档类型")
    private String documentType;

    @Schema(description = "文档业务编码，重复上传相同docCode时先删旧再插新（更新语义）")
    private String docCode;

    @Schema(description = "租户编码，用于多租户数据隔离")
    private String tenantCode;

    @Schema(description = "系统类型，用于按系统区分数据")
    private String systemType;

    @Schema(description = "上传者用户编码，用于权限控制（只有上传者可以删除）")
    private String userId;

}
