package com.motcs.core.knowledge.record;

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
    @Query("SELECT * FROM chat_message WHERE session_id = :sessionId ORDER BY create_time LIMIT :limit")
    Flux<ChatMessage> findBySessionId(String sessionId, int limit);

    /**
     * 按会话ID查询最近N条对话（按时间倒序，支持分页offset）
     */
    @Query("SELECT * FROM chat_message WHERE session_id = :sessionId ORDER BY create_time DESC LIMIT :limit OFFSET :offset")
    Flux<ChatMessage> findRecentBySessionId(String sessionId, int limit, int offset);

    /**
     * 按会话ID正序分页查询（导出用，按时间正序）
     */
    @Query("SELECT * FROM chat_message WHERE session_id = :sessionId ORDER BY create_time LIMIT :limit OFFSET :offset")
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

    // ============ API Key 维度（对话归属校验与检索） ============

    /**
     * 按 API Key + 用户/租户/系统（可选）查询最近对话记录（倒序）
     * 条件为空时不过滤：调用方需将 blank 转为 null 传入
     */
    @Query("SELECT * FROM chat_message WHERE api_key_id = :apiKeyId "
            + "AND (:userId IS NULL OR :userId = '' OR user_id = :userId) "
            + "AND (:tenantCode IS NULL OR :tenantCode = '' OR tenant_code = :tenantCode) "
            + "AND (:systemType IS NULL OR :systemType = '' OR system_type = :systemType) "
            + "ORDER BY create_time DESC LIMIT 500")
    Flux<ChatMessage> findByApiKey(Long apiKeyId, String userId, String tenantCode, String systemType);

    /**
     * 按会话ID + API Key 查询对话消息（正序，校验归属）
     */
    @Query("SELECT * FROM chat_message WHERE session_id = :sessionId AND api_key_id = :apiKeyId ORDER BY create_time")
    Flux<ChatMessage> findBySessionIdAndApiKey(String sessionId, Long apiKeyId);

    /**
     * 统计某会话中属于指定 API Key 的消息数（用于归属校验）
     */
    @Query("SELECT COUNT(*) FROM chat_message WHERE session_id = :sessionId AND api_key_id = :apiKeyId")
    Mono<Long> countBySessionIdAndApiKey(String sessionId, Long apiKeyId);

    /**
     * 按会话ID + API Key 删除对话记录（仅删除属于该 Key 的消息）
     */
    @Query("DELETE FROM chat_message WHERE session_id = :sessionId AND api_key_id = :apiKeyId")
    Mono<Integer> deleteBySessionIdAndApiKey(String sessionId, Long apiKeyId);

}
