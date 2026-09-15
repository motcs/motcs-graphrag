package com.motcs.core.knowledge.graph;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 多跳ai智能查询参数封装
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
@Schema(description = "GraphRAG 智能问答请求参数")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GraphRagRequest {

    @Schema(description = "用户提问", example = "会议的核心内容是什么？", requiredMode = Schema.RequiredMode.REQUIRED)
    private String question;

    @Schema(description = "租户编码", example = "410725")
    private String tenantCode;

    @Schema(description = "系统类型", example = "congress")
    private String systemType;

    @Schema(description = "用户编码（区分用户）", example = "user001")
    private String userId;

    @Schema(description = "会话ID（多轮对话分组，同一会话共享上下文）", example = "sess_abc123")
    private String sessionId;

    @Schema(description = "向量检索相似度阈值（默认0.5）", example = "0.5")
    private Double threshold;

    @Schema(description = "向量检索返回数量（默认5）", example = "5")
    private Integer topK;

    @Schema(description = "AI模型名称（前端选择，空则用配置默认模型）", example = "deepseek-v3.2")
    private String model;

    @Schema(description = "AI 回答", example = "ai内容...")
    private String answer;

    @Schema(description = "来源 JSON", example = "文档json数组，包含文档id、标题、来源、内容等信息")
    private String sources;

    @Schema(description = "思考过程", example = "思考内容...")
    private String reasoning;

    public Double getThreshold() {
        return threshold == null ? 0.5d : threshold;
    }

    public Integer getTopK() {
        return topK == null ? 5 : topK;
    }

}
