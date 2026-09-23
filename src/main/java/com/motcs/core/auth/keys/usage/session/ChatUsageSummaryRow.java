package com.motcs.core.auth.keys.usage.session;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 全量对话用量汇总行：所有 Key 对话的合计 token 与花费。
 */
@Schema(description = "全量对话用量汇总（所有 Key 合计）")
@Data
@AllArgsConstructor
public class ChatUsageSummaryRow {

    @Schema(description = "总对话次数")
    private Long totalCalls;

    @Schema(description = "总输入 token")
    private Long promptTokens;

    @Schema(description = "总输出 token")
    private Long completionTokens;

    @Schema(description = "总 token")
    private Long totalTokens;

    @Schema(description = "缓存 token")
    private Long cacheTokens;

    @Schema(description = "缓存花费（元）")
    private Double cacheCost;

    @Schema(description = "输入花费（元）")
    private Double inputCost;

    @Schema(description = "输出花费（元）")
    private Double outputCost;

    @Schema(description = "总花费（元）")
    private Double totalCost;

}
