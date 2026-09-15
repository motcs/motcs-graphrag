package com.motcs.core.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量删除会话请求 DTO（DELETE /documents/v1/conversations/batch）
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionRequest {

    @Schema(description = "会话ID列表", example = "会话ID列表组")
    private List<String> sessionIds;

    @Schema(description = "会话ID", example = "会话ID")
    private String sessionId;

    @Schema(description = "新标题", example = "新标题")
    private String title;

}
