package com.motcs.core.auth.keys;

import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
public interface ApiKeyUsageRepository extends ReactiveCrudRepository<ApiKeyUsage, Long> {

    /**
     * 按 Key 查询调用次数
     */
    Mono<Long> countByApiKeyId(Long apiKeyId);

    /**
     * 按 Key 查询全部调用记录（内存聚合总 token；管理端规模足够，避免复杂聚合 SQL）
     */
    Flux<ApiKeyUsage> findByApiKeyIdOrderByCreatedTimeDesc(Long apiKeyId);

    /**
     * 按 Key 分页查询调用明细（监控明细页）
     */
    Flux<ApiKeyUsage> findByApiKeyIdOrderByCreatedTimeDesc(Long apiKeyId, Pageable pageable);

}
