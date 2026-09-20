package com.motcs.core.knowledge.record;

import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 对话会话主表 Repository
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-20 星期六
 */
public interface ChatSessionRepository extends ReactiveCrudRepository<ChatSession, Long> {

    Mono<ChatSession> findBySessionId(String sessionId);

    @Query("DELETE FROM chat_session WHERE session_id = :sessionId")
    Mono<Void> deleteBySessionId(String sessionId);

    @Query("UPDATE chat_session SET title = :title WHERE session_id = :sessionId")
    Mono<Integer> updateTitleBySessionId(String title, String sessionId);

    /**
     * 统计用户+租户+系统的会话总数
     */
    @Query("SELECT COUNT(*) FROM chat_session WHERE user_id = :userId " +
            "AND (:tenantCode IS NULL OR tenant_code = :tenantCode) " +
            "AND (:systemType IS NULL OR system_type = :systemType)")
    Mono<Long> countSessions(String userId, String tenantCode, String systemType);

    /**
     * 按 API Key + 用户/租户/系统分页查询会话列表
     */
    @Query("SELECT * FROM chat_session WHERE api_key_id = :apiKeyId " +
            "AND (:userId IS NULL OR user_id = :userId) " +
            "AND (:tenantCode IS NULL OR tenant_code = :tenantCode) " +
            "AND (:systemType IS NULL OR system_type = :systemType) " +
            "ORDER BY update_time DESC")
    Flux<ChatSession> findSessionsByApiKey(Long apiKeyId, String userId, String tenantCode, String systemType, Pageable pageable);

    /**
     * 统计按 API Key + 用户/租户/系统的会话总数
     */
    @Query("SELECT COUNT(*) FROM chat_session WHERE api_key_id = :apiKeyId " +
            "AND (:userId IS NULL OR user_id = :userId) " +
            "AND (:tenantCode IS NULL OR tenant_code = :tenantCode) " +
            "AND (:systemType IS NULL OR system_type = :systemType)")
    Mono<Long> countSessionsByApiKey(Long apiKeyId, String userId, String tenantCode, String systemType);

}

