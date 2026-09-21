package com.motcs.core.knowledge.record.session;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
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
    Mono<Integer> updateTitle(String title, String sessionId);

}

