package com.motcs.core.auth.keys.usage.session;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 平台对话会话用量总表（chat_session_usage）：按会话(sessionId)聚合，
 * 每个会话累计消耗的 token 与花费，标题跟随会话标题。
 */
@Schema(description = "平台对话会话用量总表")
@Table("chat_session_usage")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionUsage implements Serializable {

    @Id
    private Long id;

    @Column("session_id")
    private String sessionId;

    @Column("user_id")
    private String userId;

    @Column("title")
    private String title;

    @Column("chat_count")
    private Integer chatCount;

    @Column("input_tokens")
    private Long inputTokens;

    @Column("output_tokens")
    private Long outputTokens;

    @Column("reasoning_tokens")
    private Long reasoningTokens;

    @Column("cache_tokens")
    private Long cacheTokens;

    @Column("total_tokens")
    private Long totalTokens;

    @Column("input_cost")
    private Double inputCost;

    @Column("output_cost")
    private Double outputCost;

    @Column("total_cost")
    private Double totalCost;

    @Column("created_time")
    private LocalDateTime createdTime;

    @Column("updated_time")
    private LocalDateTime updatedTime;
}
