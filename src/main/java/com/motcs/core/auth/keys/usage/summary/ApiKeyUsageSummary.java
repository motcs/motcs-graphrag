package com.motcs.core.auth.keys.usage.summary;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * API Key 用量汇总表（MySQL R2DBC）：按 API Key 聚合的使用量快照。
 * <p>
 * 设计目的：用量监控总览不再每次全量扫描 api_key_usage 明细表后在内存聚合，
 * 而是在每次 {@code /keys/v1/chat} 结束记录明细时，同步把本次 token 用量累加到本表
 * （一行一个 api_key_id）。监控页面直接查本表（JOIN api_key 取名称/租户），
 * 按 total_tokens 降序分页，查询极快。
 * <p>
 * 与明细表的关系：明细表 api_key_usage 保留每次调用的流水（用于单 Key 明细弹窗），
 * 本表是其按 Key 的实时累加结果。
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-19 星期六
 */
@Schema(description = "API Key 用量汇总（按 Key 聚合，一行一个 Key）")
@Table("api_key_usage_summary")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKeyUsageSummary implements Serializable {

    @Id
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "API Key 主键ID（唯一）")
    @Column("api_key_id")
    private Long apiKeyId;

    @Schema(description = "累计调用次数")
    @Column("total_calls")
    private Long totalCalls;

    @Schema(description = "累计输入 token")
    @Column("prompt_tokens")
    private Long promptTokens;

    @Schema(description = "累计输出 token")
    @Column("completion_tokens")
    private Long completionTokens;

    @Schema(description = "累计总 token")
    @Column("total_tokens")
    private Long totalTokens;

    @Schema(description = "累计缓存命中 token")
    @Column("cache_tokens")
    private Long cacheTokens;

    @Schema(description = "最近调用时间")
    @Column("last_used_at")
    private LocalDateTime lastUsedAt;

    @Schema(description = "记录更新时间")
    @Column("updated_time")
    private LocalDateTime updatedTime;
}
