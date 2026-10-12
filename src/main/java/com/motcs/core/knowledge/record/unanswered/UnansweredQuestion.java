package com.motcs.core.knowledge.record.unanswered;

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
 * 本地知识库无法回复的问题明细
 * 当本地知识库检索无命中、或有命中但 AI 判定无法作答时记录，供后台定期统计。
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-10-12 星期一
 */
@Schema(description = "本地知识库无法回复的问题明细")
@Table("unanswered_question")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnansweredQuestion implements Serializable {

    @Id
    @Schema(description = "主键ID", example = "1")
    private Long id;

    @Schema(description = "无法回答的用户问题")
    private String question;

    @Column("user_id")
    @Schema(description = "用户ID")
    private String userId;

    @Column("session_id")
    @Schema(description = "会话ID")
    private String sessionId;

    @Column("tenant_code")
    @Schema(description = "租户编码")
    private String tenantCode;

    @Column("system_type")
    @Schema(description = "系统类型")
    private String systemType;

    @Schema(description = "状态：OPEN=待处理 ANSWERED=已解决", example = "OPEN")
    private String status;

    @Schema(description = "AI回答内容")
    private String answer;

    @Schema(description = "AI思考过程")
    private String reasoning;

    @Schema(description = "AI回答引用文档(JSON数组)")
    private String sources;

    @Column("create_time")
    @Schema(description = "记录时间")
    private LocalDateTime createTime;
}
