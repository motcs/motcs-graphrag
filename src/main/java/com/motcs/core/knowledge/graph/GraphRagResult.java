package com.motcs.core.knowledge.graph;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GraphRAG 多跳查询结果 DTO
 * 只返回字符串字段，避免 OPTIONAL MATCH 的 null 实体导致映射错误
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GraphRagResult {

    @Schema(description = "源分片")
    private String sourceContent;

    @Schema(description = "源分片提及的实体名")
    private String entity1;

    @Schema(description = "多跳关联的实体名")
    private String entity2;

    @Schema(description = "关联分片内容")
    private String otherContent;

}
