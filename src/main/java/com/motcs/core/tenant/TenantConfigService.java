package com.motcs.core.tenant;

import com.motcs.commons.ContextUtil;
import com.motcs.commons.base.DatabaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-20 星期日
 */
@Service
@RequiredArgsConstructor
public class TenantConfigService extends DatabaseService {

    private final TenantConfigRepository tenantConfigRepository;

    public Flux<TenantConfig> findByEnabledTrueOrderByIdAsc() {
        return tenantConfigRepository.findByEnabledTrueOrderByIdAsc();
    }

    public Mono<Long> countSearch(String keyword) {
        return tenantConfigRepository.countSearch(keyword);
    }

    public Mono<TenantConfig> findByTenantCode(String tenantCode) {
        return tenantConfigRepository.findByTenantCode(tenantCode);
    }

    public Mono<TenantConfig> save(TenantConfig tenantConfig) {
        if (ObjectUtils.isEmpty(tenantConfig.getId())) {
            return this.tenantConfigRepository.save(tenantConfig);
        }
        return this.tenantConfigRepository.findById(tenantConfig.getId()).flatMap(old -> {
            tenantConfig.setCreatedTime(old.getCreatedTime());
            return this.tenantConfigRepository.save(tenantConfig);
        });
    }

    public Mono<Void> deleteById(Long id) {
        return this.tenantConfigRepository.deleteById(id);
    }

    public Mono<Boolean> existsById(Long id) {
        return this.tenantConfigRepository.existsById(id);
    }

    public Mono<List<TenantConfig>> search(String keyword, Pageable pageable) {
        StringBuilder builder = new StringBuilder("SELECT * FROM tenant_config");
        Map<String, Object> hashMap = new HashMap<>();
        if (StringUtils.hasLength(keyword)) {
            builder.append(" WHERE (:keyword IS NULL OR :keyword = '' OR tenant_name LIKE CONCAT('%', :keyword, '%'))");
            hashMap.put("keyword", keyword);
        }
        builder.append(ContextUtil.applyPage(pageable));
        return super.queryWith(builder.toString(), hashMap, TenantConfig.class).collectList();
    }

}
