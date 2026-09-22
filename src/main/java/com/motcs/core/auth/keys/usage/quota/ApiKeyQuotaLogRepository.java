package com.motcs.core.auth.keys.usage.quota;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * API Key 额度变更流水仓库。
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-22 星期二
 */
public interface ApiKeyQuotaLogRepository extends ReactiveCrudRepository<ApiKeyQuotaLog, Long> {

    @Query("SELECT * FROM api_key_quota_log WHERE api_key_id = :apiKeyId ORDER BY created_time DESC, id DESC")
    Flux<ApiKeyQuotaLog> findByApiKeyId(Long apiKeyId);

    Mono<ApiKeyQuotaLog> save(ApiKeyQuotaLog log);
}
