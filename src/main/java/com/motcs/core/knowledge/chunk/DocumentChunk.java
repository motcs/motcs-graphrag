package com.motcs.core.knowledge.chunk;

import com.motcs.core.knowledge.KnowledgeEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.CompositeProperty;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Schema(description = "文档分片节点（Neo4j）")
@Node("DocumentChunk")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunk {

    @Id
    @Schema(description = "分片ID")
    private String id;

    @Schema(description = "原始文档业务ID，同一个文件所有分片共用")
    private String documentId;

    @Schema(description = "文档业务编码，用户上传时指定，用于标识同一份文件、支持更新语义")
    private String docCode;

    @Schema(description = "租户编码，用于多租户数据隔离")
    private String tenantCode;

    @Schema(description = "系统类型，用于按系统区分数据")
    private String systemType;

    @Schema(description = "是否启用，启用时才允许知识被检索调用")
    @Builder.Default
    private Boolean enabled = true;

    @Schema(description = "文档处理状态：PROCESSING/SUCCESS/FAILED")
    @Builder.Default
    private String status = "PROCESSING";

    @Schema(description = "处理失败时的错误信息")
    private String errorMessage;

    @Schema(description = "源文件名")
    private String fileName;

    @Schema(description = "上传者用户编码，用于权限控制")
    private String userId;

    @Schema(description = "分片文本内容")
    private String content;

    @Schema(description = "分片序号")
    private Integer chunkIndex;

    @Schema(description = "分片所属页码，跨页使用范围格式如 \"1-2\"")
    @Builder.Default
    private String pageNumber = "1";

    @Schema(description = "切片类型：FINE细粒度/COARSE粗粒度")
    @Builder.Default
    private String chunkType = "FINE";

    @Schema(description = "父级粗粒度切片ID（细粒度切片有值，用于多跳推理补全上下文）")
    private String parentChunkId;

    @Schema(description = "上一相邻切片ID（用于上下文串联）")
    private String prevChunkId;

    @Schema(description = "下一相邻切片ID（用于上下文串联）")
    private String nextChunkId;

    @Schema(description = "层级标题链路（JSON数组字符串）")
    private String titleHierarchy;

    @Schema(description = "切片实际Token数")
    private Integer tokenSize;

    @Schema(description = "元数据，展开为节点独立属性")
    @CompositeProperty
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @Schema(description = "该分片提及的知识实体列表")
    @Relationship(type = "MENTIONS", direction = Relationship.Direction.OUTGOING)
    private List<KnowledgeEntity> mentionEntities;

}
