package com.motcs.core.auth.keys;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 用量监控总览行（api_key LEFT JOIN api_key_usage_summary 投影）。
 * <p>从未使用的 Key 也会出现（用量字段为 0），按 total_tokens 降序。
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-19 星期六
 */
public record UsageOverviewRow(
        @Schema(description = "API Key 主键ID") Long id,
        @Schema(description = "用途备注") String name,
        @Schema(description = "Key 前缀掩码") String keyPrefix,
        @Schema(description = "租户编码") String tenantCode,
        @Schema(description = "系统类型") String systemType,
        @Schema(description = "是否启用") Boolean enabled,
        @Schema(description = "累计调用次数") Long totalCalls,
        @Schema(description = "累计输入 token") Long promptTokens,
        @Schema(description = "累计输出 token") Long completionTokens,
        @Schema(description = "累计总 token") Long totalTokens,
        @Schema(description = "最近调用时间") LocalDateTime lastUsedAt,
        @Schema(description = "Key 创建时间（apikey 管理列表用，监控总览不使用）") LocalDateTime createdTime) {
}
