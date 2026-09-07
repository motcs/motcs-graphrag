package com.motcs.core.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 手动保存对话记录请求 DTO（POST /api/documents/conversations）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationRequest {

    @Schema(description = "用户提问", example = "你好啊....")
    private String question;

    @Schema(description = "AI 回答", example = "ai内容...")
    private String answer;

    @Schema(description = "用户编码", example = "用户唯一标识符")
    private String userId;

    @Schema(description = "会话ID", example = "会话唯一标识符")
    private String sessionId;

    @Schema(description = "来源 JSON", example = "文档json数组，包含文档id、标题、来源、内容等信息")
    private String sources;

    @Schema(description = "思考过程", example = "思考内容...")
    private String reasoning;

    @Schema(description = "租户编码", example = "410725")
    private String tenantCode;

    @Schema(description = "系统类型", example = "other")
    private String systemType;

}
