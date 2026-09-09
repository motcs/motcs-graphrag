package com.motcs.core.auth.keys;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * API Key 使用监控记录（MySQL R2DBC）：每次 API Key 对话消耗的 token 明细
 * <p>
 * 记录时机：/keys/v1/chat 对话流结束后，从 AI 响应元数据中取出 TokenUsage 落库；
 * 管理端按 Key 查看调用次数与 token 消耗（总量/明细），用于额度监控。
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
@Schema(description = "API Key 使用记录（token 消耗明细）")
@Table("api_key_usage")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKeyUsage {

    @Id
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "API Key 主键ID")
    @Column("api_key_id")
    private Long apiKeyId;

    @Schema(description = "调用方用户编码")
    @Column("user_id")
    private String userId;

    @Schema(description = "会话ID")
    @Column("session_id")
    private String sessionId;

    @Schema(description = "使用的模型")
    @Column("model")
    private String model;

    @Schema(description = "输入 token 数")
    @Column("prompt_tokens")
    private Integer promptTokens;

    @Schema(description = "输出 token 数")
    @Column("completion_tokens")
    private Integer completionTokens;

    @Schema(description = "总 token 数")
    @Column("total_tokens")
    private Integer totalTokens;

    @Schema(description = "调用时间")
    @Column("created_time")
    private LocalDateTime createdTime;
}
