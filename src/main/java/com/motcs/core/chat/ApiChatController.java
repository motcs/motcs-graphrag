package com.motcs.core.chat;

import com.motcs.commons.ContextUtil;
import com.motcs.commons.utils.Utils;
import com.motcs.core.auth.keys.ApiKeyService;
import com.motcs.core.auth.keys.usage.ApiKeyUsageService;
import com.motcs.core.auth.keys.usage.quota.ModelPricing;
import com.motcs.core.knowledge.graph.GraphRagRequest;
import com.motcs.core.knowledge.graph.GraphRagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * API Key 专用对话接口（GraphRAG 问答，SSE 流式返回）
 * <pre>
 *   POST /keys/v1/chat
 *   请求头：Authorization: Bearer sk-... 或 X-API-Key: sk-...
 *   Body: {"question":"...","userId":"...","sessionId":"...(可选)","model":"...(可选)"}
 * </pre>
 * 设计要点：
 * - 租户编码 / 系统类型由 API Key 绑定值自动赋值，调用方只需传 问题/用户编码/会话ID；
 * - 对话历史按 API Key 隔离（chat_message.api_key_id = 该 Key 的 id）；
 * - 每次对话的 token 消耗（prompt/completion/total）记录到 api_key_usage，供管理端用量监控；
 * - 仅 API Key 认证可访问（SecurityConfiguration /keys/v1/** = hasRole(API_KEY)，
 * 登录用户无 API_KEY 角色将被 403），API Key 请求自动豁免 CSRF；
 * - SSE 事件格式与 /documents/v1/query 一致：
 * {"type":"session","sessionId":...} → {"type":"sources","sources":[...]}
 * → {"type":"reasoning"|"content","text":...}
 */
@Log4j2
@RestController
@RequestMapping("/keys/v1")
@RequiredArgsConstructor
public class ApiChatController {

    @Value("${app.ai.default-chat-model:}")
    private String defaultChatModel;
    private final ApiKeyService apiKeyService;
    private final GraphRagService graphRagService;
    private final ApiKeyUsageService apiKeyUsageService;

    /**
     * API Key 对话（SSE）：问题与用户编码必填，租户/系统来自 Key 绑定值；流结束后记录 token 消耗
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody GraphRagRequest request, ServerWebExchange exchange) {
        // SSE 流式输出：告知反向代理（nginx）不缓冲该响应，逐帧透传
        exchange.getResponse().getHeaders().set("X-Accel-Buffering", "no");
        exchange.getResponse().getHeaders().set("Cache-Control", "no-cache, no-transform");
        if (ObjectUtils.isEmpty(request) || ObjectUtils.isEmpty(request.getQuestion())) {
            return Flux.just(Utils.jsonEvent("error", Map.of("message", "问题（question）不能为空")));
        }
        if (ObjectUtils.isEmpty(request.getUserId())) {
            return Flux.just(Utils.jsonEvent("error", Map.of("message", "用户编码（userId）不能为空")));
        }
        String sessionId = ObjectUtils.isEmpty(request.getSessionId()) ?
                UUID.randomUUID().toString() : request.getSessionId();

        return this.apiKeyService.resolveApiKey(exchange).flatMapMany(apiKey -> {
            if (!this.apiKeyService.hasQuota(apiKey)) {
                return Flux.just(Utils.jsonEvent("error", Map.of("message", "非常抱歉，您的额度已用尽，不能正常对话！")));
            }
            // 租户/系统类型由 API Key 绑定值赋值（生成时已禁止租户 0）
            request.setSessionId(sessionId);
            request.setApiKeyId(apiKey.getId());
            request.setTenantCode(apiKey.getTenantCode());
            if (ObjectUtils.isEmpty(request.getModel())) {
                request.setModel(defaultChatModel);
            }
            if (ObjectUtils.isEmpty(request.getSystemType())) {
                request.setSystemType(apiKey.getSystemType());
            }
            StringBuilder answerBuilder = new StringBuilder();
            StringBuilder reasoningBuilder = new StringBuilder();

            return graphRagService.graphRagQueryStream(request).flatMapMany(result -> {
                // 直接持有 QueryResult 内部的 usage 引用（流结束时可读到最终 token 用量）
                AtomicReference<Usage> usageRef = result.usageRef();
                JsonNode sourcesJson;
                try {
                    sourcesJson = ContextUtil.OBJECT_MAPPER.convertValue(result.sources(), JsonNode.class);
                } catch (Exception e) {
                    sourcesJson = ContextUtil.OBJECT_MAPPER.createArrayNode();
                }
                Mono<String> sessionMono = Mono.just(Utils.jsonEvent("session", Map.of("sessionId", sessionId)));
                Mono<String> rewriteMono = Mono.just(Utils.jsonEvent("rewrite", Map.of("query", result.rewriteQuery())));
                Mono<String> sourcesMono = Mono.just(Utils.jsonEvent("sources", Map.of("sources", result.sources())));
                Flux<String> answerMono = result.answer().doOnNext(ev -> {
                    if ("reasoning".equals(ev.type())) {
                        reasoningBuilder.append(ev.text());
                    } else if ("content".equals(ev.type())) {
                        answerBuilder.append(ev.text());
                    }
                }).map(ev -> Utils.jsonEvent(ev.type(), Map.of("text", ev.text() == null ? "" : ev.text())));
                JsonNode finalSourcesJson = sourcesJson;
                return Flux.concat(sessionMono, rewriteMono, sourcesMono, answerMono)
                        .publishOn(Schedulers.boundedElastic())
                        .doFinally(signal -> {
                            if (signal == SignalType.CANCEL) return;
                            if (StringUtils.hasLength(request.getQuestion())) {
                                request.setAnswer(answerBuilder.toString());
                                request.setSessionId(sessionId);
                                request.setSources(finalSourcesJson);
                                request.setReasoning(reasoningBuilder.toString());
                                this.graphRagService.saveConversation(request).subscribe();
                                // 记录 token 用量（用量监控）
                                Usage usage = usageRef.get();
                                if (usage != null) {
                                    this.apiKeyUsageService.record(apiKey.getId(), request.getUserId(), sessionId,
                                            request.getModel(), usage.getPromptTokens(), usage.getCompletionTokens(),
                                            usage.getTotalTokens(),
                                            usage.getCacheReadInputTokens() == null ? 0 : usage.getCacheReadInputTokens().intValue()).subscribe();
                                    ModelPricing.Price price = ModelPricing.of(request.getModel());
                                    int prompt = usage.getPromptTokens();
                                    int completion = usage.getCompletionTokens();
                                    int cache = usage.getCacheReadInputTokens() == null ? 0 : usage.getCacheReadInputTokens().intValue();
                                    int uncached = Math.max(prompt - cache, 0);
                                    double cost = uncached / 1000.0 * price.inPerK()
                                            + cache / 1000.0 * price.cachePerK()
                                            + completion / 1000.0 * price.outPerK();
                                    this.apiKeyService.consumeQuota(apiKey.getId(), cost).subscribe();
                                }
                            }
                        });
            });
        }).switchIfEmpty(Flux.just(Utils.jsonEvent("error", Map.of("message", "无效的 API Key"))));
    }

}
