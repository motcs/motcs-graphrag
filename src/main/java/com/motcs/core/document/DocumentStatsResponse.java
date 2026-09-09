package com.motcs.core.document;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档统计响应 DTO
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
@Schema(description = "文档统计信息")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStatsResponse {

    @Schema(description = "文档数量", example = "10")
    private Long docCount;

    @Schema(description = "分片数量", example = "150")
    private Long chunkCount;

}
