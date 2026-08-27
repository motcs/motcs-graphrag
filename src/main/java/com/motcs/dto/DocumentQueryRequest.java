package com.motcs.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档查询请求 DTO（统计、列表等 GET 接口统一接参）
 */
@Schema(description = "文档查询请求参数")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentQueryRequest {

    @Schema(description = "租户编码", example = "410725")
    private String tenantCode;

    @Schema(description = "系统类型", example = "congress")
    private String systemType;

    @Schema(description = "用户编码", example = "user001")
    private String userId;
}
