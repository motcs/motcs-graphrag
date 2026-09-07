package com.motcs.core.knowledge.record;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * 对话记录（MySQL R2DBC 存储）
 * 记录用户提问与 AI 回答，用于历史查询和多轮上下文
 */
@Schema(description = "对话记录")
@Table("chat_message")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    @Id
    @Schema(description = "主键ID", example = "1")
    private Long id;

    @Schema(description = "用户编码（区分用户）", example = "user001")
    @Column("user_id")
    private String userId;

    @Schema(description = "会话ID（多轮对话分组）", example = "sess_abc123")
    @Column("session_id")
    private String sessionId;

    @Schema(description = "会话标题（每条记录冗余存储，取最后一条即可）", example = "工作会议纪要问答")
    @Column("title")
    private String title;

    @Schema(description = "用户提问", example = "这个文档的核心内容是什么？")
    @Column("question")
    private String question;

    @Schema(description = "AI回答", example = "该文档主要讲述了...")
    @Column("answer")
    private String answer;

    @Schema(description = "AI思考内容", example = "思考...")
    @Column("reasoning")
    private String reasoning;

    @Schema(description = "引用的知识库来源（JSON数组字符串，数据库列为JSON类型）")
    @Column("sources")
    private JsonNode sources;

    @Schema(description = "租户编码", example = "410725")
    @Column("tenant_code")
    private String tenantCode;

    @Schema(description = "系统类型", example = "congress")
    @Column("system_type")
    private String systemType;

    @Schema(description = "创建该对话的 API Key ID（空=登录用户创建）", example = "1")
    @Column("api_key_id")
    private Long apiKeyId;

    @Schema(description = "创建时间", example = "2026-08-25 14:30:00")
    @Column("create_time")
    private LocalDateTime createTime;

}
