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
             completion_tokens, total_tokens, input_cost, output_cost, last_used_at, updated_time)
             VALUES (:apiKeyId, 1, :promptTokens, :completionTokens, :totalTokens, :inputCost, :outputCost, :now, :now)
             ON DUPLICATE KEY UPDATE total_calls = total_calls + 1, prompt_tokens = prompt_tokens + :promptTokens,
             completion_tokens = completion_tokens + :completionTokens, total_tokens = total_tokens + :totalTokens, cache_tokens = cache_tokens + :cacheTokens,
             input_cost = input_cost + :inputCost, output_cost = output_cost + :outputCost,
             last_used_at = IF(:now > last_used_at OR last_used_at IS NULL, :now, last_used_at), updated_time = :now
            """)
    Mono<Long> incrementUsage(Long apiKeyId, long promptTokens, long completionTokens, long totalTokens,
                              long cacheTokens, double inputCost, double outputCost, LocalDateTime now);

    /**
     * 全局合计（汇总表 SUM，用于监控页顶部卡片）
     */
    @Query("""
            SELECT COALESCE(SUM(total_calls), 0)       AS total_calls,
                   COALESCE(SUM(prompt_tokens), 0)    AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                   COALESCE(SUM(total_tokens), 0)     AS total_tokens,
                   COALESCE(SUM(input_cost), 0)       AS input_cost,
                   COALESCE(SUM(output_cost), 0)      AS output_cost,
                   COALESCE(SUM(cache_tokens), 0)      AS cache_tokens
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
     * 花费按明细表记录的 model 套用单价计算（与 ModelPricing 一致）。
     */
    @Modifying
    @Query("TRUNCATE TABLE api_key_usage_summary")
    Mono<Void> truncate();

    @Modifying
    @Query("""
            INSERT INTO api_key_usage_summary(api_key_id, total_calls, prompt_tokens,
             completion_tokens, total_tokens, input_cost, output_cost, last_used_at, updated_time)
             SELECT api_key_id, COUNT(*),
             COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(completion_tokens), 0),
             COALESCE(SUM(total_tokens), 0),
             COALESCE(SUM(CASE model
                 WHEN 'deepseek-v4.1-flash' THEN prompt_tokens / 1000.0 * 0.002
                 WHEN 'deepseek-v4-flash-0731' THEN prompt_tokens / 1000.0 * 0.003
                 WHEN 'glm-5.3-flash' THEN prompt_tokens / 1000.0 * 0.0008
                 WHEN 'ernie-4.5-turbo-20260402' THEN prompt_tokens / 1000.0 * 0.0008
                 WHEN 'ernie-4.5-turbo-128k' THEN prompt_tokens / 1000.0 * 0.0008 ELSE 0 END), 0),
             COALESCE(SUM(CASE model
                 WHEN 'deepseek-v4.1-flash' THEN completion_tokens / 1000.0 * 0.008
                 WHEN 'deepseek-v4-flash-0731' THEN completion_tokens / 1000.0 * 0.009
                 WHEN 'glm-5.3-flash' THEN completion_tokens / 1000.0 * 0.0028
                 WHEN 'ernie-4.5-turbo-20260402' THEN completion_tokens / 1000.0 * 0.0032
                 WHEN 'ernie-4.5-turbo-128k' THEN completion_tokens / 1000.0 * 0.0032 ELSE 0 END), 0),
             MAX(created_time), NOW() FROM api_key_usage
             where api_key_id in (select id from api_key) GROUP BY api_key_id
            """)
    Mono<Long> rebuildFromDetail();

    /**
     * 把汇总表里的历史花费（输入+输出成本）同步回 api_key.used_quota。
     * 老数据升级后执行一次，让已有 Key 的已用额度等于历史总花费。
     */
    @Query("""
            UPDATE api_key k SET used_quota = COALESCE((SELECT s.input_cost + s.output_cost FROM
             api_key_usage_summary s WHERE s.api_key_id = k.id), 0) where is_delete = 0
            """)
    Mono<Long> syncUsedQuota();

}
