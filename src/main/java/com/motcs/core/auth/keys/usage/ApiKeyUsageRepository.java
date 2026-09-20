package com.motcs.core.auth.keys.usage;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

/**
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
public interface ApiKeyUsageRepository extends ReactiveCrudRepository<ApiKeyUsage, Long> {

}
