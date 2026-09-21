package com.motcs.core.auth.keys.usage.summary;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * API Key 用量汇总仓库（api_key_usage_summary）。
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-19 星期六
 */
public interface ApiKeyUsageSummaryRepository extends ReactiveCrudRepository<ApiKeyUsageSummary, Long> {

    /**
     * 按 api_key_id 查汇总行（不存在返回 empty）
     */
    Mono<ApiKeyUsageSummary> findByApiKeyId(Long apiKeyId);

    /**
     * 增量累加一次调用用量（upsert）：
     * 存在则在原值上累加，不存在则插入新行。
     * 唯一键 uk_summary_api_key(api_key_id) 保证一行一 Key。
     */
    @Modifying
    @Query("""
            INSERT INTO api_key_usage_summary(api_key_id, total_calls, prompt_tokens,
             completion_tokens, total_tokens, last_used_at, updated_time)
             VALUES (:apiKeyId, 1, :promptTokens, :completionTokens, :totalTokens, :now, :now)
             ON DUPLICATE KEY UPDATE total_calls = total_calls + 1, prompt_tokens = prompt_tokens + :promptTokens,
             completion_tokens = completion_tokens + :completionTokens, total_tokens = total_tokens + :totalTokens,
             last_used_at = IF(:now > last_used_at OR last_used_at IS NULL, :now, last_used_at), updated_time = :now
            """)
    Mono<Long> incrementUsage(Long apiKeyId, long promptTokens, long completionTokens, long totalTokens, LocalDateTime now);

    /**
     * 全局合计（汇总表 SUM，用于监控页顶部卡片）
     */
    @Query("""
            SELECT COALESCE(SUM(total_calls), 0)       AS total_calls,
                   COALESCE(SUM(prompt_tokens), 0)    AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                   COALESCE(SUM(total_tokens), 0)     AS total_tokens
             FROM api_key_usage_summary where api_key_id in (select id from api_key)
            """)
    Mono<UsageOverviewRow> globalTotals();

    /**
     * 汇总表是否为空（用于启动时判断是否需要从明细表初始化）
     */
    @Query("SELECT COUNT(*) FROM api_key_usage_summary")
    Mono<Long> countSummary();

    /**
     * 从明细表全量重建汇总表（初始化/修复用）：
     * 按 api_key_id 聚合明细表后写入汇总表（先清空再写）。
     */
    @Modifying
    @Query("TRUNCATE TABLE api_key_usage_summary")
    Mono<Void> truncate();

    @Modifying
    @Query("""
            INSERT INTO api_key_usage_summary(api_key_id, total_calls, prompt_tokens,
             completion_tokens, total_tokens, last_used_at, updated_time) SELECT api_key_id, COUNT(*),
             COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(completion_tokens), 0),
             COALESCE(SUM(total_tokens), 0), MAX(created_time), NOW() FROM api_key_usage
             where api_key_id in (select id from api_key) GROUP BY api_key_id
            """)
    Mono<Long> rebuildFromDetail();

    Mono<Void> deleteByApiKeyId(Long apiKeyId);

}
