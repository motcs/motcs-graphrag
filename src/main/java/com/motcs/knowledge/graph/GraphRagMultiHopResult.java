package com.motcs.knowledge.graph;

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
public class GraphRagMultiHopResult {

    /** 源分片 内容 */
    private String sourceContent;

    /** 源分片提及的实体名 */
    private String entity1;

    /** 多跳关联的实体名（可能为null） */
    private String entity2;

    /** 关联分片内容（可能为null） */
    private String otherContent;

}
