package com.motcs.core.auth.keys.usage.summary;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface ApiKeyUsageSummaryRepository extends ReactiveCrudRepository<ApiKeyUsageSummary, Long> {

    Mono<ApiKeyUsageSummary> findByApiKeyId(Long apiKeyId);

    @Modifying
    @Query("""
            INSERT INTO api_key_usage_summary(api_key_id, total_calls, prompt_tokens,
             completion_tokens, total_tokens, cache_tokens, input_cost, output_cost, cache_cost,
             last_used_at, updated_time)
             VALUES (:apiKeyId, 1, :promptTokens, :completionTokens, :totalTokens, :cacheTokens,
             :inputCost, :outputCost, :cacheCost, :now, :now)
             ON DUPLICATE KEY UPDATE total_calls = total_calls + 1, prompt_tokens = prompt_tokens + :promptTokens,
             completion_tokens = completion_tokens + :completionTokens, total_tokens = total_tokens + :totalTokens,
             cache_tokens = cache_tokens + :cacheTokens,
             input_cost = input_cost + :inputCost, output_cost = output_cost + :outputCost,
             cache_cost = cache_cost + :cacheCost,
             last_used_at = IF(:now > last_used_at OR last_used_at IS NULL, :now, last_used_at), updated_time = :now
            """)
    Mono<Long> incrementUsage(Long apiKeyId, long promptTokens, long completionTokens, long totalTokens,
                              long cacheTokens, double inputCost, double outputCost, double cacheCost, LocalDateTime now);

    @Query("""
            SELECT COALESCE(SUM(total_calls), 0)       AS total_calls,
                   COALESCE(SUM(prompt_tokens), 0)    AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                   COALESCE(SUM(total_tokens), 0)     AS total_tokens,
                   COALESCE(SUM(cache_tokens), 0)     AS cache_tokens,
                   COALESCE(SUM(input_cost), 0)       AS input_cost,
                   COALESCE(SUM(output_cost), 0)      AS output_cost,
                   COALESCE(SUM(cache_cost), 0)       AS cache_cost
             FROM api_key_usage_summary where api_key_id in (select id from api_key)
            """)
    Mono<UsageOverviewRow> globalTotals();

    @Query("SELECT COUNT(*) FROM api_key_usage_summary")
    Mono<Long> countSummary();

    @Modifying
    @Query("TRUNCATE TABLE api_key_usage_summary")
    Mono<Void> truncate();

    @Modifying
    @Query("""
            INSERT INTO api_key_usage_summary(api_key_id, total_calls, prompt_tokens,
             completion_tokens, total_tokens, cache_tokens, input_cost, output_cost, cache_cost,
             last_used_at, updated_time)
             SELECT api_key_id, COUNT(*),
             COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(completion_tokens), 0),
             COALESCE(SUM(total_tokens), 0), COALESCE(SUM(cache_tokens), 0),
             COALESCE(SUM(CASE model
                 WHEN 'deepseek-v4.1-flash' THEN (prompt_tokens - COALESCE(cache_tokens,0)) / 1000.0 * 0.002
                 WHEN 'deepseek-v4-flash-0731' THEN (prompt_tokens - COALESCE(cache_tokens,0)) / 1000.0 * 0.003
                 WHEN 'glm-5.3-flash' THEN (prompt_tokens - COALESCE(cache_tokens,0)) / 1000.0 * 0.0008
                 WHEN 'ernie-4.5-turbo-20260402' THEN (prompt_tokens - COALESCE(cache_tokens,0)) / 1000.0 * 0.0008
                 WHEN 'ernie-4.5-turbo-128k' THEN (prompt_tokens - COALESCE(cache_tokens,0)) / 1000.0 * 0.0008 ELSE 0 END), 0),
             COALESCE(SUM(CASE model
                 WHEN 'deepseek-v4.1-flash' THEN completion_tokens / 1000.0 * 0.008
                 WHEN 'deepseek-v4-flash-0731' THEN completion_tokens / 1000.0 * 0.009
                 WHEN 'glm-5.3-flash' THEN completion_tokens / 1000.0 * 0.0028
                 WHEN 'ernie-4.5-turbo-20260402' THEN completion_tokens / 1000.0 * 0.0032
                 WHEN 'ernie-4.5-turbo-128k' THEN completion_tokens / 1000.0 * 0.0032 ELSE 0 END), 0),
             COALESCE(SUM(CASE model
                 WHEN 'deepseek-v4.1-flash' THEN cache_tokens / 1000.0 * 0.0002
                 WHEN 'deepseek-v4-flash-0731' THEN cache_tokens / 1000.0 * 0.0003
                 WHEN 'glm-5.3-flash' THEN cache_tokens / 1000.0 * 0.00023
                 WHEN 'ernie-4.5-turbo-20260402' THEN cache_tokens / 1000.0 * 0.0002
                 WHEN 'ernie-4.5-turbo-128k' THEN cache_tokens / 1000.0 * 0.0002 ELSE 0 END), 0),
             MAX(created_time), NOW() FROM api_key_usage
             where api_key_id in (select id from api_key) GROUP BY api_key_id
            """)
    Mono<Long> rebuildFromDetail();

    @Query("""
            UPDATE api_key k SET used_quota = COALESCE(
                (SELECT s.input_cost + s.output_cost + COALESCE(s.cache_cost,0) FROM api_key_usage_summary s WHERE s.api_key_id = k.id), 0)
            """)
    Mono<Long> syncUsedQuota();
}