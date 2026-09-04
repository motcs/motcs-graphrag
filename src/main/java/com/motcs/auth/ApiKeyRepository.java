package com.motcs.auth;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

/**
 * API Key R2DBC Repository
 */
public interface ApiKeyRepository extends ReactiveCrudRepository<ApiKey, Long> {

    /**
     * 按 Key 哈希精确查询（启用状态）用于请求鉴权
     */
    @Query("SELECT * FROM api_key WHERE key_hash = :hash AND enabled = 1 LIMIT 1")
    Mono<ApiKey> findEnabledByKeyHash(String hash);
}
