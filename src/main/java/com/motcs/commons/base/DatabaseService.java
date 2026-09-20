package com.motcs.commons.base;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.r2dbc.convert.R2dbcConverter;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Query;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;

/**
 * 数据库服务基类
 * 提供了一些基础的数据库操作方法
 * 包括带有缓存的查询、分页查询等
 * 子类需要实现afterPropertiesSet方法，以便初始化entityTemplate和databaseClient
 *
 * @author <a href="https://github.com/vnobo">Alex bob</a>
 */
@Log4j2
@Component
public abstract class DatabaseService extends AbstractService {

    protected R2dbcEntityTemplate entityTemplate;
    protected DatabaseClient databaseClient;
    protected R2dbcConverter r2dbcConverter;

    protected <T> Flux<T> queryWith(Query query, Class<T> entityClass) {
        // 构造查询请求，并对结果进行排序
        return this.entityTemplate.select(query, entityClass);
    }

    protected <T> Flux<T> queryWith(String query, Map<String, Object> bindParams, Class<T> entityClass) {
        // Create a GenericExecuteSpec object from the given query
        var executeSpec = this.databaseClient.sql(() -> query);
        // Bind the given parameters to the query
        for (var e : bindParams.entrySet()) {
            executeSpec = executeSpec.bind(e.getKey(), e.getValue());
        }
        // Read the results of the query into an entity class
        return executeSpec.map((row, rowMetadata) -> r2dbcConverter.read(entityClass, row, rowMetadata)).all();
    }

    protected Mono<Long> countWith(String query, Map<String, Object> bindParameters) {
        // Create a statement from the given query
        var executeStatement = this.databaseClient.sql(() -> query);
        // Bind all parameters to the statement
        for (Map.Entry<String, Object> entry : bindParameters.entrySet()) {
            executeStatement = executeStatement.bind(entry.getKey(), entry.getValue());
        }
        // Get the first element of the result as a Long
        return executeStatement.map(readable -> Objects.requireNonNull(readable.get(0, Long.class))).one();
    }


    /**
     * 初始化entityTemplate和databaseClient
     */
    @Override
    public void afterPropertiesSet() {
        this.r2dbcConverter = this.entityTemplate.getConverter();
        this.databaseClient = this.entityTemplate.getDatabaseClient();
    }

    @Autowired
    public void setEntityTemplate(R2dbcEntityTemplate entityTemplate) {
        this.entityTemplate = entityTemplate;
    }

}