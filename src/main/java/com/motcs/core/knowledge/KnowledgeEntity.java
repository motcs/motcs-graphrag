package com.motcs.core.knowledge;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.List;

@Schema(description = "知识实体节点（Neo4j）")
@Node("KnowledgeEntity")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeEntity {

    @Id
    @GeneratedValue
    @Schema(description = "实体ID")
    private Long id;

    @Schema(description = "实体名称", example = "张三")
    private String name;

    @Schema(description = "实体类型：人物/组织/概念/地点等", example = "人物")
    private String type;

    @Schema(description = "关联的其他实体列表")
    @Relationship(type = "RELATE_TO", direction = Relationship.Direction.OUTGOING)
    private List<KnowledgeEntity> relateEntities;

}
