package com.motcs.core.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建 API Key 请求 DTO（POST /auth/v1/api-keys）
 * 备注/租户编码/系统类型均必填
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKeyRequest {

    @Schema(description = "用途备注（必填）", example = "第三方对接")
    private String name;

    @Schema(description = "绑定的租户编码（必填）：对话/上传文档归属", example = "410725")
    private String tenantCode;

    @Schema(description = "绑定的系统类型（必填）：对话/上传文档归属", example = "other")
    private String systemType;

}
