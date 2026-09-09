package com.motcs.core.knowledge;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
public interface KnowledgeEntityRepository extends Neo4jRepository<KnowledgeEntity, Long> {

    /**
     * 按实体名称查询（用于去重，避免重复创建同名实体）
     */
    @Query("MATCH (e:KnowledgeEntity{name:$name}) RETURN e")
    Optional<KnowledgeEntity> findByName(@Param("name") String name);

}
