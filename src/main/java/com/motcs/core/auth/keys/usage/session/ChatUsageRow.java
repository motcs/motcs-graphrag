package com.motcs.core.auth.keys.usage.session;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 平台对话会话用量行：每个会话(sessionId)累计消耗。
 */
@Schema(description = "平台对话会话用量行")
public record ChatUsageRow(
        @Schema(description = "记录ID") Long id,
        @Schema(description = "会话ID") String sessionId,
        @Schema(description = "用户编码") String userId,
        @Schema(description = "会话标题") String title,
        @Schema(description = "对话轮数") Integer chatCount,
        @Schema(description = "输入 token") Long inputTokens,
        @Schema(description = "输出 token") Long outputTokens,
        @Schema(description = "推理 token") Long reasoningTokens,
        @Schema(description = "缓存命中 token") Long cacheTokens,
        @Schema(description = "总 token") Long totalTokens,
        @Schema(description = "输入花费(元)") Double inputCost,
        @Schema(description = "输出花费(元)") Double outputCost,
        @Schema(description = "缓存花费(元)") Double cacheCost,
        @Schema(description = "总花费(元)") Double totalCost,
        @Schema(description = "最近使用时间") LocalDateTime updatedTime
) {
}
