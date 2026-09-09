package com.motcs.core.auth.keys;

import com.motcs.config.SecurityConfiguration;
import com.motcs.core.knowledge.graph.GraphRagService;
import com.motcs.core.knowledge.record.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * API Key 专属对话历史接口（请求头必须携带有效 API Key，登录态不可访问，见 SecurityConfiguration /keys/v1/**）
 * <pre>
 *   GET    /keys/v1/conversations                     按 apikey + 用户名 + 租户 + 系统类型 查询会话列表（后三者可选）
 *   GET    /keys/v1/session?sessionId=  按会话ID查询消息（仅本 Key 创建的）
 *   DELETE /keys/v1/session/{sessionId} 删除单个会话（仅限本 Key 创建的，否则 404）
 *   DELETE /keys/v1/batch               批量删除（Body: ["sess1","sess2"]，仅删本 Key 的，返回实际删除数）
 * </pre>
 * 鉴权头：Authorization: Bearer sk-... 或 X-API-Key: sk-...（解析规则与 SecurityConfiguration.extractApiKey 一致）
 */
@Slf4j
@RestController
@RequestMapping("/keys/v1")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final GraphRagService graphRagService;

    /**
     * 查询会话列表：按 apikey + 用户名 + 租户 + 系统类型
     * userId / tenantCode / systemType 不传或为空时不过滤
     */
    @GetMapping
    public Mono<List<Map<String, Object>>> listSessions(ServerWebExchange exchange,
                                                        @RequestParam(value = "userId", required = false) String userId,
                                                        @RequestParam(value = "tenantCode", required = false) String tenantCode,
                                                        @RequestParam(value = "systemType", required = false) String systemType) {
        return resolveApiKey(exchange).flatMap(apiKey -> graphRagService
                .getSessionsByApiKey(apiKey.getId(), userId, tenantCode, systemType, 500));
    }

    /**
     * 按会话ID查询消息（仅本 Key 创建的；不属于该 Key 返回 404）
     */
    @GetMapping("/session")
    public Mono<List<ChatMessage>> sessionMessages(ServerWebExchange exchange, @RequestParam("sessionId") String sessionId) {
        return resolveApiKey(exchange).flatMap(apiKey -> graphRagService
                .getConversationsBySessionAndApiKey(sessionId, apiKey.getId()));
    }

    /**
     * 删除单个会话（仅限本 Key 创建的；不属于该 Key 返回 404，不删除任何数据）
     */
    @DeleteMapping("/session/{sessionId}")
    public Mono<ResponseEntity<Map<String, Object>>> deleteSession(ServerWebExchange exchange, @PathVariable String sessionId) {
        return resolveApiKey(exchange).flatMap(apiKey -> graphRagService.deleteSessionByApiKey(sessionId, apiKey.getId())
                .map(deleted -> deleted ? ResponseEntity.ok(Map.of("success", true, "deleted", 1))
                        : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "success", false, "message", "会话不存在或不属于当前 API Key"))));
    }

    /**
     * 批量删除（Body: sessionId 数组；仅删除本 Key 创建的会话，返回实际删除数）
     */
    @DeleteMapping("/batch")
    public Mono<ResponseEntity<Map<String, Object>>> deleteBatch(ServerWebExchange exchange, @RequestBody List<String> sessionIds) {
        if (ObjectUtils.isEmpty(sessionIds)) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "sessionIds 不能为空")));
        }
        return resolveApiKey(exchange).flatMap(apiKey -> {
            Mono<Integer> deleteMono = graphRagService.deleteSessionsByApiKey(sessionIds, apiKey.getId());
            return deleteMono.map(n -> {
                Map<String, Object> success = Map.of("success", true, "deleted", n);
                return ResponseEntity.ok(success);
            });
        });
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

}
