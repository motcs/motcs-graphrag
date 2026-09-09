package com.motcs.core.knowledge.record;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 会话摘要实体（每个会话只存一份，用于长对话压缩上下文，减少token消耗）
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
@Data
@Table("chat_session_summary")
@Schema(description = "会话摘要")
public class ChatSessionSummary {

    @Id
    @Schema(description = "主键ID")
    private Long id;

    @Column("session_id")
    @Schema(description = "会话ID", example = "sess_abc123")
    private String sessionId;

    @Column("title")
    @Schema(description = "会话主题名称（AI生成或用户自定义，生成后固定不变）")
    private String title;

    @Column("summary")
    @Schema(description = "历史对话摘要内容")
    private String summary;

    @Column("last_message_count")
    @Schema(description = "上次摘要时的消息总数")
    private Integer lastMessageCount;

    @Column("created_time")
    @Schema(description = "创建时间")
    private LocalDateTime createdTime;

    @Column("updated_time")
    @Schema(description = "更新时间")
    private LocalDateTime updatedTime;
}
