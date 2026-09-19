package com.motcs.core.auth.keys;

import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
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
            INSERT INTO api_key_usage_summary
              (api_key_id, total_calls, prompt_tokens, completion_tokens, total_tokens, last_used_at, updated_time)
            VALUES
              (:apiKeyId, 1, :promptTokens, :completionTokens, :totalTokens, :now, :now)
            ON DUPLICATE KEY UPDATE
              total_calls      = total_calls + 1,
              prompt_tokens    = prompt_tokens + :promptTokens,
              completion_tokens = completion_tokens + :completionTokens,
              total_tokens     = total_tokens + :totalTokens,
              last_used_at     = CASE WHEN :now > last_used_at OR last_used_at IS NULL THEN :now ELSE last_used_at END,
              updated_time     = :now
            """)
    Mono<Long> incrementUsage(Long apiKeyId, long promptTokens, long completionTokens, long totalTokens, LocalDateTime now);

    /**
     * 监控总览分页：api_key LEFT JOIN 汇总表，按 total_tokens 降序。
     * 从未使用的 Key 用量为 0，排在已使用 Key 之后。
     */
    @Query("""
            SELECT k.id            AS id,
                   k.name          AS name,
                   k.key_prefix    AS key_prefix,
                   k.tenant_code   AS tenant_code,
                   k.system_type   AS system_type,
                   k.enabled       AS enabled,
                   COALESCE(s.total_calls, 0)      AS total_calls,
                   COALESCE(s.prompt_tokens, 0)    AS prompt_tokens,
                   COALESCE(s.completion_tokens, 0) AS completion_tokens,
                   COALESCE(s.total_tokens, 0)     AS total_tokens,
                   s.last_used_at  AS last_used_at
            FROM api_key k
            LEFT JOIN api_key_usage_summary s ON s.api_key_id = k.id
            ORDER BY COALESCE(s.total_tokens, 0) DESC, k.id ASC
            """)
    Flux<UsageOverviewRow> findOverview(Pageable pageable);

    /**
     * API Key 管理列表分页：api_key LEFT JOIN 汇总表带用量，按创建时间降序。
     */
    @Query("""
            SELECT k.id            AS id,
                   k.name          AS name,
                   k.key_prefix    AS key_prefix,
                   k.tenant_code   AS tenant_code,
                   k.system_type   AS system_type,
                   k.enabled       AS enabled,
                   COALESCE(s.total_calls, 0)      AS total_calls,
                   COALESCE(s.prompt_tokens, 0)    AS prompt_tokens,
                   COALESCE(s.completion_tokens, 0) AS completion_tokens,
                   COALESCE(s.total_tokens, 0)     AS total_tokens,
                   s.last_used_at  AS last_used_at,
                   k.created_time  AS created_time
            FROM api_key k
            LEFT JOIN api_key_usage_summary s ON s.api_key_id = k.id
            ORDER BY k.created_time DESC, k.id DESC
            """)
    Flux<UsageOverviewRow> findApiKeyList(Pageable pageable);

    /**
     * Key 总数（监控总览分页总数）
     */
    @Query("SELECT COUNT(*) FROM api_key")
    Mono<Long> countAllKeys();

    /**
     * 全局合计（汇总表 SUM，用于监控页顶部卡片）
     */
    @Query("""
            SELECT COALESCE(SUM(total_calls), 0)       AS total_calls,
                   COALESCE(SUM(prompt_tokens), 0)    AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                   COALESCE(SUM(total_tokens), 0)     AS total_tokens
            FROM api_key_usage_summary
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
            INSERT INTO api_key_usage_summary
              (api_key_id, total_calls, prompt_tokens, completion_tokens, total_tokens, last_used_at, updated_time)
            SELECT api_key_id,
                   COUNT(*),
                   COALESCE(SUM(prompt_tokens), 0),
                   COALESCE(SUM(completion_tokens), 0),
                   COALESCE(SUM(total_tokens), 0),
                   MAX(created_time),
                   NOW()
            FROM api_key_usage
            GROUP BY api_key_id
            """)
    Mono<Long> rebuildFromDetail();
}
