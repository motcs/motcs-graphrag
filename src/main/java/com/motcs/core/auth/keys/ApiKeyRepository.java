package com.motcs.core.auth.keys;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

/**
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
public interface ApiKeyRepository extends ReactiveCrudRepository<ApiKey, Long> {

    /**
     * 按 Key 哈希精确查询（启用状态）用于请求鉴权
     */
    @Query("SELECT * FROM api_key WHERE key_hash = :hash AND enabled = 1 LIMIT 1")
    Mono<ApiKey> findEnabledByKeyHash(String hash);

}
