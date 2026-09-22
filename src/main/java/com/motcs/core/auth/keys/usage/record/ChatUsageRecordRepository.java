package com.motcs.core.auth.keys.usage.record;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

/**
 * 平台对话用量记录仓库。
 */
public interface ChatUsageRecordRepository extends ReactiveCrudRepository<ChatUsageRecord, Long> {
}
