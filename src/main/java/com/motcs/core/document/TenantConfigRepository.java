package com.motcs.core.document;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 租户配置仓库（tenant_config）
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
public interface TenantConfigRepository extends ReactiveCrudRepository<TenantConfig, Long> {

    /**
     * 查询启用的租户（按 id 升序，保证列表稳定）
     */
    Flux<TenantConfig> findByEnabledTrueOrderByIdAsc();

    /**
     * 按租户编码查询（唯一键，用于新增查重）
     */
    Mono<TenantConfig> findByTenantCode(String tenantCode);
}
