package com.motcs.core.knowledge.graph;

import com.motcs.commons.ContextUtil;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于独立部署的 BGE Reranker（Python 服务，motcs-reranker）的 HTTP 精排实现。
 * <p>
 * 使用 JDK {@link HttpClient} 同步调用，避免 WebClient.block() 在 reactor 非阻塞线程上被禁止的问题；
 * 请求/响应均用显式 Map 构造/解析（record 在 ContextUtil.OBJECT_MAPPER 下会序列化为 {}，导致服务 422）。
 * 配置 app.ai.reranker.enabled=true 时启用；调用失败（连接/超时/非200/解析失败）抛出异常，
 * 由 GraphRagService 捕获并回退本地精排；服务正常但判定无相关（200 且 results 为空）时返回空列表。
 */
@Service
@Log4j2
@ConditionalOnProperty(prefix = "app.ai.reranker", name = "enabled", havingValue = "true")
public class HttpRerankerService implements RerankerService {

    @Value("${app.ai.reranker.url:http://127.0.0.1:8000}")
    private String rerankerUrl;

    @Value("${app.ai.reranker.timeout-ms:2000}")
    private long timeoutMs;

    /**
     * 精排后分数下限：低于该值的候选视为无关，不返回
     */
    @Value("${app.ai.reranker.threshold:0.85}")
    private double threshold;

    @Override
    public List<Document> rerank(String query, List<Document> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        try {
            // 过滤掉文本为空/null 的候选，避免 Python 端 422（text 为必填 str）
            List<Map<String, Object>> docs = candidates.stream()
                    .filter(d -> d.getText() != null && !d.getText().isBlank())
                    .map(d -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("id", d.getId());
                        m.put("text", d.getText());
                        return m;
                    }).collect(Collectors.toList());
            if (docs.isEmpty()) {
                return List.of();
            }

            // 请求体用显式 Map（字段名与 Python 服务完全一致）
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", query);
            payload.put("documents", docs);
            payload.put("top_n", topN);
            String body = ContextUtil.OBJECT_MAPPER.writeValueAsString(payload);
            log.warn("[Reranker] 请求 body: {}", body);

            HttpClient client = HttpClient.newBuilder()
                    // 强制 HTTP/1.1：JDK 默认发起 h2c 升级，uvicorn(HTTP/1.1) 会丢 body 导致 422
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(rerankerUrl + "/v1/rerank"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("Reranker 非 200 响应: " + resp.statusCode());
            }

            JsonNode root = ContextUtil.OBJECT_MAPPER.readTree(resp.body());
            JsonNode resultsNode = root.get("results");
            if (resultsNode == null || !resultsNode.isArray()) {
                // 服务正常但无结果 → 判定无相关，不强制回退本地
                return List.of();
            }
            Set<String> orderedIds = new LinkedHashSet<>();
            for (JsonNode n : resultsNode) {
                JsonNode idNode = n.get("id");
                JsonNode scoreNode = n.get("score");
                boolean pass = idNode != null && !idNode.isNull()
                        && (scoreNode == null || scoreNode.isNull() || scoreNode.asDouble() >= threshold);
                if (pass) {
                    orderedIds.add(idNode.asString());
                }
            }
            Map<String, Document> byId = candidates.stream()
                    .collect(Collectors.toMap(Document::getId, d -> d, (a, _) -> a));
            List<Document> ordered = new ArrayList<>();
            for (String id : orderedIds) {
                Document d = byId.get(id);
                if (d != null) {
                    ordered.add(d);
                }
            }
            log.debug("外部 Reranker 精排: 输入 {} 候选 → 保留 {} 条", candidates.size(), ordered.size());
            return ordered;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Reranker 调用失败: " + e.getMessage(), e);
        }
    }
}
