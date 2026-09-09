package com.motcs.core.chat;

import com.motcs.commons.ContextUtil;
import com.motcs.config.SecurityConfiguration;
import com.motcs.core.auth.keys.ApiKey;
import com.motcs.core.auth.keys.ApiKeyService;
import com.motcs.core.knowledge.graph.GraphRagRequest;
import com.motcs.core.knowledge.graph.GraphRagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
 * - 仅 API Key 认证可访问（SecurityConfiguration /keys/v1/** = hasRole(API_KEY)，
 * 登录用户无 API_KEY 角色将被 403），API Key 请求自动豁免 CSRF；
 * - SSE 事件格式与 /documents/v1/query 一致：
 * {"type":"session","sessionId":...} → {"type":"sources","sources":[...]}
 * → {"type":"reasoning"|"content","text":...}
 */
@Slf4j
@RestController
@RequestMapping("/keys/v1")
@RequiredArgsConstructor
public class ApiChatController {

    private final ApiKeyService apiKeyService;
    private final GraphRagService graphRagService;

    /**
     * API Key 对话（SSE）：问题与用户编码必填，租户/系统来自 Key 绑定值
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody GraphRagRequest request, ServerWebExchange exchange) {
        if (ObjectUtils.isEmpty(request) || ObjectUtils.isEmpty(request.getQuestion())) {
            return Flux.just(jsonEvent("error", Map.of("message", "问题（question）不能为空")));
        }
        if (ObjectUtils.isEmpty(request.getUserId())) {
            return Flux.just(jsonEvent("error", Map.of("message", "用户编码（userId）不能为空")));
        }
        String sessionId = ObjectUtils.isEmpty(request.getSessionId()) ? UUID.randomUUID().toString() : request.getSessionId();

        return resolveApiKey(exchange).flatMapMany(apiKey -> {
            // 租户/系统类型由 API Key 绑定值赋值（生成时已禁止租户 0）
            request.setSessionId(sessionId);
            request.setTenantCode(apiKey.getTenantCode());
            request.setSystemType(apiKey.getSystemType());
            StringBuilder answerBuilder = new StringBuilder();
            StringBuilder reasoningBuilder = new StringBuilder();
            final String[] sourcesJson = {"[]"};
            return graphRagService.graphRagQueryStream(request).flatMapMany(result -> {
                try {
                    sourcesJson[0] = ContextUtil.OBJECT_MAPPER.writeValueAsString(result.sources());
                } catch (Exception e) {
                    sourcesJson[0] = "[]";
                }
                Mono<String> sessionMono = Mono.just(jsonEvent("session", Map.of("sessionId", sessionId)));
                Mono<String> sourcesMono = Mono.just(jsonEvent("sources", Map.of("sources", result.sources())));
                Flux<String> answerMono = result.answer().doOnNext(ev -> {
                    if ("reasoning".equals(ev.type())) {
                        reasoningBuilder.append(ev.text());
                    } else if ("content".equals(ev.type())) {
                        answerBuilder.append(ev.text());
                    }
                }).map(ev -> jsonEvent(ev.type(), Map.of("text", ev.text() == null ? "" : ev.text())));
                return Flux.concat(sessionMono, sourcesMono, answerMono);
            }).publishOn(Schedulers.boundedElastic()).doFinally(signal -> {
                if (signal == reactor.core.publisher.SignalType.CANCEL) return;
                if (StringUtils.hasLength(request.getQuestion())) {
                    request.setAnswer(answerBuilder.toString());
                    request.setSessionId(sessionId);
                    request.setSources(sourcesJson[0]);
                    request.setReasoning(reasoningBuilder.toString());
                    graphRagService.saveConversation(request, apiKey.getId()).subscribe();
                }
            });
        }).switchIfEmpty(Flux.just(jsonEvent("error", Map.of("message", "无效的 API Key"))));
    }

    /**
     * 从请求头解析并校验 API Key（与 SecurityConfiguration.extractApiKey 同一规则）：
     * Authorization: Bearer sk-... 或 X-API-Key: sk-...；无效/缺失返回 empty
     */
    private Mono<ApiKey> resolveApiKey(ServerWebExchange exchange) {
        String key = SecurityConfiguration.extractApiKey(exchange);
        if (key == null || key.isBlank()) {
            return Mono.empty();
        }
        return apiKeyService.findEnabled(key);
    }

    private String jsonEvent(String type, Map<String, ?> payload) {
        try {
            Map<String, Object> ev = new HashMap<>();
            if (payload != null) ev.putAll(payload);
            ev.put("type", type);
            return ContextUtil.OBJECT_MAPPER.writeValueAsString(ev);
        } catch (Exception e) {
            return "{\"type\":\"" + type + "\"}";
        }
    }
}
