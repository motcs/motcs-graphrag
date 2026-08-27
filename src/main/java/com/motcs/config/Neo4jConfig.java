package com.motcs.config;

import com.motcs.knowledge.chunk.DocumentChunk;
import com.motcs.knowledge.KnowledgeEntity;
import jakarta.annotation.PostConstruct;
import org.neo4j.driver.Driver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.neo4j.core.Neo4jTemplate;
import org.springframework.data.neo4j.core.convert.Neo4jConversions;
import org.springframework.data.neo4j.core.mapping.Neo4jMappingContext;
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionManager;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;

import java.util.Set;

/**
 * Spring Data Neo4j 显式配置。
 * Spring Boot 4.x 的 spring-boot-starter-neo4j 仅自动配置 Driver，
 * 不再自动配置 Neo4jTemplate / MappingContext，需要手动声明。
 */
@Configuration
@EnableNeo4jRepositories(basePackages = "com.motcs.knowledge")
public class Neo4jConfig {

    private final Driver driver;

    public Neo4jConfig(Driver driver) {
        this.driver = driver;
    }

    /**
     * 启动时创建唯一约束，防止 KnowledgeEntity.name 重复（并行实体抽取的竞态条件）
     */
    @PostConstruct
    public void initConstraints() {
        try (var session = driver.session()) {
            session.run("CREATE CONSTRAINT knowledge_entity_name_unique IF NOT EXISTS " +
                    "FOR (e:KnowledgeEntity) REQUIRE e.name IS UNIQUE");
        } catch (Exception e) {
            // 约束已存在或数据库不支持时忽略
        }
    }

    @Bean
    public Neo4jConversions neo4jConversions() {
        return new Neo4jConversions();
    }

    @Bean
    public Neo4jMappingContext neo4jMappingContext(Neo4jConversions neo4jConversions) {
        Neo4jMappingContext context = new Neo4jMappingContext(neo4jConversions);
        context.setInitialEntitySet(Set.of(DocumentChunk.class, KnowledgeEntity.class));
        context.afterPropertiesSet();
        return context;
    }

    @Bean
    public Neo4jClient neo4jClient(Driver driver) {
        return Neo4jClient.create(driver);
    }

    @Bean
    public Neo4jTransactionManager transactionManager(Driver driver) {
        return new Neo4jTransactionManager(driver);
    }

    @Bean
    public Neo4jTemplate neo4jTemplate(Neo4jClient neo4jClient, Neo4jMappingContext neo4jMappingContext) {
        return new Neo4jTemplate(neo4jClient, neo4jMappingContext);
    }

}
