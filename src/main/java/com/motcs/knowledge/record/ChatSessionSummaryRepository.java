package com.motcs.knowledge.record;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

/**
 * 会话摘要 R2DBC Repository
 */
public interface ChatSessionSummaryRepository extends ReactiveCrudRepository<ChatSessionSummary, Long> {

    /**
     * 按会话ID查询摘要
     */
    Mono<ChatSessionSummary> findBySessionId(String sessionId);

    /**
     * 按会话ID删除摘要
     */
    @Query("DELETE FROM chat_session_summary WHERE session_id = :sessionId")
    Mono<Void> deleteBySessionId(String sessionId);

}
