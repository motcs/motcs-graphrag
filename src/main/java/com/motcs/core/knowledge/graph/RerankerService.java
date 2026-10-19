package com.motcs.core.knowledge.graph;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 检索精排（Reranker）服务：对初次召回的候选文档按"与问题相关性"精排。
 * <p>
 * 可接入独立部署的 bge-reranker / 千帆 Rerank API / 其他精排服务；
 * 未配置任何实现（无 RerankerService Bean）时，GraphRagService 使用本地精排兜底
 * （语义分数排序 + 主题一致性过滤）。
 */
public interface RerankerService {

    /**
     * 精排候选文档，按相关性从高到低排序并返回前 topN 条。
     *
     * @param query      用户问题（精排相关性基准）
     * @param candidates 初次召回的全部候选文档
     * @param topN       需要保留的最相关文档数
     * @return 精排后的文档（最多 topN 条，可为空表示都不相关）
     */
    List<Document> rerank(String query, List<Document> candidates, int topN);
}
