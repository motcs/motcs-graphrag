package com.motcs.core.document;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档响应DTO
 */
@Schema(description = "文档信息")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentResponse {

    @Schema(description = "文档ID", example = "123456")
    private Long documentId;

    @Schema(description = "文档业务编码（用户上传时指定，用于标识同一份文件）", example = "202608D2MEETING")
    private String docCode;

    @Schema(description = "租户编码", example = "410725")
    private String tenantCode;

    @Schema(description = "系统类型", example = "congress")
    private String systemType;

    @Schema(description = "是否启用（参与知识搜索）", example = "true")
    private Boolean enabled;

    @Schema(description = "文件名", example = "会议纪要.pdf")
    private String fileName;

    @Schema(description = "文档标题", example = "周工作会议纪要")
    private String title;

    @Schema(description = "文档描述", example = "2026年8月第二周会议内容")
    private String description;

    @Schema(description = "切分后的chunk数量", example = "5")
    private Integer chunkCount;

    @Schema(description = "上传状态：PROCESSING/SUCCESS/FAILED", example = "SUCCESS")
    private String status;

    @Schema(description = "错误信息（处理失败时）")
    private String errorMessage;

    @Schema(description = "上传时间", example = "2026-08-25 14:30:00")
    private LocalDateTime uploadTime;

    @Schema(description = "文件大小（字节）", example = "3360")
    private Long fileSize;

    @Schema(description = "文件存储路径")
    private String filePath;

    @Schema(description = "上传者用户编码", example = "user001")
    private String userId;

}
