package com.motcs.knowledge.chunk;

import com.motcs.dto.DocumentStatsResponse;
import com.motcs.dto.DocumentSummary;
import com.motcs.knowledge.KnowledgeEntity;
import com.motcs.knowledge.graph.GraphRagMultiHopResult;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DocumentChunkRepository extends Neo4jRepository<DocumentChunk, String> {

    /**
     * GraphRAG多跳检索
     * 1跳 chunk‑[MENTIONS]->e1
     * 2跳 e1‑[RELATE_TO*1..2]->e2
     * 3跳 otherChunk‑[MENTIONS]->e2
     * 按 tenantCode、systemType、enabled 过滤，只返回字符串字段
     */
    @Query("""
            MATCH (chunk:DocumentChunk)
             WHERE chunk.id IN $chunkIds
             AND chunk.tenantCode = $tenantCode
             AND chunk.systemType = $systemType
             AND chunk.enabled = true
             MATCH (chunk)-[:MENTIONS]->(e1:KnowledgeEntity)
             OPTIONAL MATCH (e1)-[:RELATE_TO*1..2]->(e2:KnowledgeEntity)
             OPTIONAL MATCH (otherChunk:DocumentChunk)-[:MENTIONS]->(e2)
             WHERE otherChunk IS NULL OR (otherChunk.tenantCode = $tenantCode
             AND otherChunk.systemType = $systemType
             AND otherChunk.enabled = true)
             RETURN DISTINCT chunk.content AS sourceContent,
             e1.name AS entity1,
             e2.name AS entity2,
             otherChunk.content AS otherContent
             LIMIT $limit
            """)
    List<GraphRagMultiHopResult> multiHopRetrieve(
            @Param("chunkIds") List<String> chunkIds,
            @Param("tenantCode") String tenantCode,
            @Param("systemType") String systemType,
            @Param("limit") int limit);

    /**
     * 根据docCode查询所有分片（同一份文件的所有分片）
     */
    @Query("MATCH (c:DocumentChunk{docCode:$docCode}) RETURN c")
    List<DocumentChunk> findByDocCode(@Param("docCode") String docCode);

    /**
     * 根据docCode删除所有分片及其关系（重复上传同编码文件时先清理旧数据）
     */
    @Query("MATCH (c:DocumentChunk{docCode:$docCode}) DETACH DELETE c")
    void deleteChunksByDocCode(@Param("docCode") String docCode);

    /**
     * 根据docCode查询所有分片ID（用于删除向量库）
     */
    @Query("MATCH (c:DocumentChunk{docCode:$docCode}) RETURN c.id AS id")
    List<String> findChunkIdsByDocCode(@Param("docCode") String docCode);

    /**
     * 按docCode查询仅被该文档引用的知识点
     */
    @Query("""
            MATCH (c:DocumentChunk{docCode:$docCode})-[:MENTIONS]->(e:KnowledgeEntity)
            WHERE NOT EXISTS {
                MATCH (other:DocumentChunk)-[:MENTIONS]->(e)
                WHERE other.docCode <> $docCode
            }
            RETURN e
            """)
    List<KnowledgeEntity> findExclusiveEntitiesByDocCode(@Param("docCode") String docCode);

    /**
     * 按ID批量删除知识点及其所有关系（DETACH DELETE 会同时删除 MENTIONS 和 RELATE_TO）
     */
    @Query("UNWIND $ids AS eid MATCH (e:KnowledgeEntity) WHERE id(e) = eid DETACH DELETE e")
    void deleteEntitiesByIds(@Param("ids") List<Long> ids);

    /**
     * 按 documentId 批量更新分片状态和错误信息
     * 注意：向量库节点的 metadata 属性存储为 `metadata.xxx` 格式，必须更新带前缀的属性
     */
    @Query("""
            MATCH (c:DocumentChunk{documentId:$documentId})
            SET c.`metadata.status` = $status,
                c.`metadata.errorMessage` = $errorMessage,
                c.status = $status,
                c.errorMessage = $errorMessage
            """)
    void updateStatusByDocumentId(@Param("documentId") String documentId,
                                  @Param("status") String status,
                                  @Param("errorMessage") String errorMessage);

    /**
     * 按租户统计文档数和分片数（一次聚合查询，返回 DTO）
     */
    @Query("""
            MATCH (c:DocumentChunk)
            WHERE $tenantCode IS NULL OR c.tenantCode = $tenantCode
            RETURN count(DISTINCT c.documentId) AS docCount, count(c) AS chunkCount
            """)
    DocumentStatsResponse countByTenant(@Param("tenantCode") String tenantCode);

    /**
     * 为细粒度切片（向量库已存在的节点）补充图谱属性
     * 向量库节点属性为 metadata.xxx 格式，SDN 查询需要无前缀属性，因此同时设置两套
     */
    @Query("""
            MATCH (c:DocumentChunk{id:$id})
            SET c.documentId = $documentId,
                c.docCode = $docCode,
                c.tenantCode = $tenantCode,
                c.systemType = $systemType,
                c.enabled = $enabled,
                c.fileName = $fileName,
                c.userId = $userId,
                c.content = $content,
                c.chunkIndex = $chunkIndex,
                c.pageNumber = $pageNumber,
                c.chunkType = $chunkType,
                c.parentChunkId = $parentChunkId,
                c.prevChunkId = $prevChunkId,
                c.nextChunkId = $nextChunkId,
                c.titleHierarchy = $titleHierarchy,
                c.tokenSize = $tokenSize,
                c.status = $status
            """)
    void updateFineChunkGraphProps(@Param("id") String id,
                                   @Param("documentId") String documentId,
                                   @Param("docCode") String docCode,
                                   @Param("tenantCode") String tenantCode,
                                   @Param("systemType") String systemType,
                                   @Param("enabled") Boolean enabled,
                                   @Param("fileName") String fileName,
                                   @Param("userId") String userId,
                                   @Param("content") String content,
                                   @Param("chunkIndex") Integer chunkIndex,
                                   @Param("pageNumber") String pageNumber,
                                   @Param("chunkType") String chunkType,
                                   @Param("parentChunkId") String parentChunkId,
                                   @Param("prevChunkId") String prevChunkId,
                                   @Param("nextChunkId") String nextChunkId,
                                   @Param("titleHierarchy") String titleHierarchy,
                                   @Param("tokenSize") Integer tokenSize,
                                   @Param("status") String status);

    /**
     * 文档列表聚合查询：在 Neo4j 层面直接分组聚合，只投影需要的字段，不拉取 embedding/content
     * 优先取细粒度分片元数据，回退到占位分片
     */
    @Query("""
            MATCH (c:DocumentChunk)
             WHERE c.tenantCode = $tenantCode AND c.systemType = $systemType
             WITH c.documentId AS documentId,
                  sum(CASE WHEN c.chunkIndex >= 0 AND c.chunkType <> 'COARSE' THEN 1 ELSE 0 END) AS chunkCount,
                  collect(c {.docCode, .fileName, .status, .errorMessage,
                            .userId, .tenantCode, .systemType, .enabled, .chunkIndex, .chunkType,
                            title: c.`metadata.title`, description: c.`metadata.description`,
                            uploadTime: c.`metadata.uploadTime`, fileSize: c.`metadata.fileSize`}) AS metas
             WITH documentId, chunkCount,
                  coalesce([m IN metas WHERE m.chunkIndex >= 0 AND m.chunkType <> 'COARSE'][0],
                           [m IN metas WHERE m.chunkIndex = -1][0], metas[0]) AS meta
             RETURN documentId, chunkCount,
                    meta.docCode AS docCode, meta.fileName AS fileName, meta.title AS title,
                    meta.description AS description, meta.status AS status,
                    meta.errorMessage AS errorMessage, meta.userId AS userId,
                    meta.tenantCode AS tenantCode, meta.systemType AS systemType,
                    meta.enabled AS enabled, meta.uploadTime AS uploadTime,
                    meta.fileSize AS fileSize
             ORDER BY uploadTime DESC
            """)
    List<DocumentSummary> findDocumentSummaries(@Param("tenantCode") String tenantCode,
                                                @Param("systemType") String systemType);
}
