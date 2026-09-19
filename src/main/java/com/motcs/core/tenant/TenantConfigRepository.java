package com.motcs.core.tenant;

import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
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
     * 查询启用的租户（按 id 升序，保证列表稳定）—— 供下拉框使用
     */
    Flux<TenantConfig> findByEnabledTrueOrderByIdAsc();

    /**
     * 按租户编码查询（唯一键，用于新增查重）
     */
    Mono<TenantConfig> findByTenantCode(String tenantCode);

    /**
     * 管理端分页查询：支持按租户名称模糊搜索，按 id 升序。
     * keyword 为空时不过滤；tenant_code='0' 为系统保留项，不落表，无需排除。
     */
    @Query("""
            SELECT * FROM tenant_config
            WHERE (:keyword IS NULL OR :keyword = '' OR tenant_name LIKE CONCAT('%', :keyword, '%'))
            ORDER BY id ASC
            """)
    Flux<TenantConfig> searchPage(String keyword, Pageable pageable);

    /**
     * 管理端分页总数（与 searchPage 同条件）
     */
    @Query("""
            SELECT COUNT(*) FROM tenant_config
            WHERE (:keyword IS NULL OR :keyword = '' OR tenant_name LIKE CONCAT('%', :keyword, '%'))
            """)
    Mono<Long> countSearch(String keyword);
}
