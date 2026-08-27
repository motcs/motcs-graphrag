package com.motcs.knowledge.record;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 对话记录 R2DBC Repository
 */
public interface ChatMessageRepository extends ReactiveCrudRepository<ChatMessage, Long> {

    /**
     * 按用户+租户+系统查询最近对话记录
     */
    @Query("SELECT * FROM chat_message WHERE user_id = :userId AND tenant_code = :tenantCode AND system_type = :systemType ORDER BY create_time DESC LIMIT :limit")
    Flux<ChatMessage> findByUser(String userId, String tenantCode, String systemType, int limit);

    /**
     * 按会话ID查询对话历史（多轮上下文用，按时间正序）
     */
    @Query("SELECT * FROM chat_message WHERE session_id = :sessionId ORDER BY create_time ASC LIMIT :limit")
    Flux<ChatMessage> findBySessionId(String sessionId, int limit);

    /**
     * 按会话ID查询最近N条对话（按时间倒序，支持分页offset）
     */
    @Query("SELECT * FROM chat_message WHERE session_id = :sessionId ORDER BY create_time DESC LIMIT :limit OFFSET :offset")
    Flux<ChatMessage> findRecentBySessionId(String sessionId, int limit, int offset);

    /**
     * 按会话ID正序分页查询（导出用，按时间正序）
     */
    @Query("SELECT * FROM chat_message WHERE session_id = :sessionId ORDER BY create_time ASC LIMIT :limit OFFSET :offset")
    Flux<ChatMessage> findBySessionIdAsc(String sessionId, int limit, int offset);

    /**
     * 统计会话下的对话总数
     */
    @Query("SELECT COUNT(*) FROM chat_message WHERE session_id = :sessionId")
    Mono<Long> countBySessionId(String sessionId);

    /**
     * 按会话ID删除该会话的所有对话记录
     */
    @Query("DELETE FROM chat_message WHERE session_id = :sessionId")
    Mono<Void> deleteBySessionId(String sessionId);

    /**
     * 精准查询引用了指定 docCode 的对话记录（分页）
     * 使用 LIKE 匹配 sources JSON 中的 docCode 字段
     */
    @Query("SELECT * FROM chat_message WHERE JSON_CONTAINS(sources, JSON_OBJECT('docCode', :docCode)) ORDER BY id LIMIT :limit OFFSET :offset")
    Flux<ChatMessage> findBySourcesDocCode(String docCode, int limit, int offset);

    /**
     * 批量更新某会话所有记录的标题
     */
    @Query("UPDATE chat_message SET title = :title WHERE session_id = :sessionId")
    Mono<Integer> updateTitleBySessionId(String title, String sessionId);
}
