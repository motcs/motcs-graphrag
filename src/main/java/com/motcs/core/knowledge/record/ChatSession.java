package com.motcs.core.knowledge.record;

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
 * 对话会话主表（MySQL R2DBC 存储）
 * 每个会话一条记录，记录会话元信息；对话明细在 chat_message 从表
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-20 星期六
 */
@Schema(description = "对话会话")
@Table("chat_session")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession implements Serializable {

    @Id
    @Schema(description = "主键ID", example = "1")
    private Long id;

    @Schema(description = "会话ID（业务唯一）", example = "sess_abc123")
    @Column("session_id")
    private String sessionId;

    @Schema(description = "会话标题", example = "工作会议纪要问答")
    @Column("title")
    private String title;

    @Schema(description = "用户编码", example = "user001")
    @Column("user_id")
    private String userId;

    @Schema(description = "创建该会话的 API Key ID（空=登录用户创建）", example = "1")
    @Column("api_key_id")
    private Long apiKeyId;

    @Schema(description = "租户编码", example = "410725")
    @Column("tenant_code")
    private String tenantCode;

    @Schema(description = "系统类型", example = "congress")
    @Column("system_type")
    private String systemType;

    @Schema(description = "创建时间", example = "2026-09-20 14:30:00")
    @Column("create_time")
    private LocalDateTime createTime;

    @Schema(description = "最后活跃时间", example = "2026-09-20 15:30:00")
    @Column("update_time")
    private LocalDateTime updateTime;

}
