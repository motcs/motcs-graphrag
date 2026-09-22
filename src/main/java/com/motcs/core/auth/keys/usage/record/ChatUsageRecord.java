package com.motcs.core.auth.keys.usage.record;

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
 * 平台对话用量记录（chat_usage_record）：每次平台内 /documents/v1/query 对话消耗的 token。
 * <p>与 API Key 外部调用用量（api_key_usage）分开统计：input=prompt，output=completion，
 * reasoning=推理（思考）token，cache=缓存命中 token。
 *
 * @author motcs
 */
@Schema(description = "平台对话用量记录")
@Table("chat_usage_record")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatUsageRecord implements Serializable {

    @Id
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "调用方用户编码")
    @Column("user_id")
    private String userId;

    @Schema(description = "会话ID")
    @Column("session_id")
    private String sessionId;

    @Schema(description = "使用的模型")
    @Column("model")
    private String model;

    @Schema(description = "输入 token")
    @Column("input_tokens")
    private Integer inputTokens;

    @Schema(description = "输出 token（含推理）")
    @Column("output_tokens")
    private Integer outputTokens;

    @Schema(description = "推理（思考）token")
    @Column("reasoning_tokens")
    private Integer reasoningTokens;

    @Schema(description = "缓存命中 token")
    @Column("cache_tokens")
    private Integer cacheTokens;

    @Schema(description = "调用时间")
    @Column("created_time")
    private LocalDateTime createdTime;
}
