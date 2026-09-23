package com.motcs.core.auth.keys.usage.session;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface ChatSessionUsageRepository extends ReactiveCrudRepository<ChatSessionUsage, Long> {

    /**
     * 累加一次对话到会话用量总表（不存在则插入）。
     */
    @Modifying
    @Query("""
            INSERT INTO chat_session_usage(session_id, user_id, title, chat_count, input_tokens, output_tokens,
             reasoning_tokens, cache_tokens, total_tokens, input_cost, output_cost, cache_cost, total_cost, created_time, updated_time)
             VALUES (:sessionId, :userId, :title, 1, :inputTokens, :outputTokens, :reasoningTokens, :cacheTokens, :totalTokens,
             :inputCost, :outputCost, :cacheCost, :totalCost, NOW(), NOW())
             ON DUPLICATE KEY UPDATE chat_count = chat_count + 1,
             input_tokens = input_tokens + :inputTokens, output_tokens = output_tokens + :outputTokens,
             reasoning_tokens = reasoning_tokens + :reasoningTokens, cache_tokens = cache_tokens + :cacheTokens,
             total_tokens = total_tokens + :totalTokens,
             input_cost = input_cost + :inputCost, output_cost = output_cost + :outputCost, cache_cost = cache_cost + :cacheCost,
             total_cost = total_cost + :totalCost, updated_time = NOW()
            """)
    Mono<Void> accumulate(@Param("sessionId") String sessionId, @Param("userId") String userId,
                          @Param("title") String title, @Param("inputTokens") long inputTokens,
                          @Param("outputTokens") long outputTokens, @Param("reasoningTokens") long reasoningTokens,
                          @Param("cacheTokens") long cacheTokens, @Param("totalTokens") long totalTokens,
                          @Param("inputCost") double inputCost, @Param("outputCost") double outputCost,
                          @Param("cacheCost") double cacheCost, @Param("totalCost") double totalCost);

    /**
     * 会话标题变更时同步更新用量总表标题。
     */
    @Modifying
    @Query("UPDATE chat_session_usage SET title = :title WHERE session_id = :sessionId")
    Mono<Void> updateTitle(@Param("sessionId") String sessionId, @Param("title") String title);
}
