package com.motcs.core.auth.keys;

import com.motcs.core.knowledge.graph.GraphRagService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
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
 * 鉴权头：Authorization: Bearer sk-... 或 X-API-Key: sk-...（解析规则与 SecurityConfiguration.extractApiKey 一致）；
 * 无效/缺失 Key：统一返回 401 {"code":401,"message":"无效的 API Key"}
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
@Slf4j
@RestController
@RequestMapping("/keys/v1")
@RequiredArgsConstructor
public class ApiKeyController {

    private static final Map<String, Object> UNAUTHORIZED_BODY = Map
            .of("code", 401, "message", "非常抱歉，您的密钥无效或已过期，请检查后重试。");
    private final ApiKeyService apiKeyService;
    private final GraphRagService graphRagService;

    @GetMapping
    @Operation(summary = "会话列表")
    public Mono<ResponseEntity<?>> listSessions(ServerWebExchange exchange,
                                                @RequestParam(value = "userId", required = false) String userId,
                                                @RequestParam(value = "tenantCode", required = false) String tenantCode,
                                                @RequestParam(value = "systemType", required = false) String systemType,
                                                Pageable pageable) {
        return this.apiKeyService.resolveApiKey(exchange).flatMap(apiKey ->
                        this.graphRagService.getSessionsByApiKey(apiKey.getId(), userId, tenantCode, systemType, pageable)
                                .<ResponseEntity<?>>map(ResponseEntity::ok))
                .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(UNAUTHORIZED_BODY)));
    }

    @GetMapping("/session")
    @Operation(summary = "按会话ID查询消息（仅本 Key 创建的；不属于该 Key 返回空列表）")
    public Mono<ResponseEntity<?>> sessionMessages(ServerWebExchange exchange, @RequestParam("sessionId") String sessionId) {
        return this.apiKeyService.resolveApiKey(exchange).flatMap(apiKey -> this.graphRagService
                        .getConversationsBySessionAndApiKey(sessionId, apiKey.getId()).<ResponseEntity<?>>map(ResponseEntity::ok))
                .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(UNAUTHORIZED_BODY)));
    }

    @DeleteMapping("/session/{sessionId}")
    @Operation(summary = "删除单个会话（仅限本 Key 创建的；不属于该 Key 返回 404，不删除任何数据）")
    public Mono<ResponseEntity<Map<String, Object>>> deleteSession(ServerWebExchange exchange, @PathVariable String sessionId) {
        return this.apiKeyService.resolveApiKey(exchange).<ResponseEntity<Map<String, Object>>>flatMap(apiKey ->
                this.graphRagService.deleteSessionByApiKey(sessionId, apiKey.getId()).map(deleted -> {
                    if (deleted) {
                        return ResponseEntity.ok(Map.of("success", true, "deleted", 1));
                    }
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                            "success", false, "message", "会话不存在或不属于当前 API Key"));
                })).switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(UNAUTHORIZED_BODY)));
    }

    @DeleteMapping("/batch")
    @Operation(summary = "批量删除（Body: sessionId 数组；仅删除本 Key 创建的会话，返回实际删除数）")
    public Mono<ResponseEntity<Map<String, Object>>> deleteBatch(ServerWebExchange exchange, @RequestBody List<String> sessionIds) {
        if (ObjectUtils.isEmpty(sessionIds)) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "sessionIds 不能为空")));
        }
        return this.apiKeyService.resolveApiKey(exchange).flatMap(apiKey -> {
            Mono<Integer> deleteMono = graphRagService.deleteSessionsByApiKey(sessionIds, apiKey.getId());
            return deleteMono.map(n -> {
                Map<String, Object> success = Map.of("success", true, "deleted", n);
                return ResponseEntity.ok(success);
            });
        }).switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(UNAUTHORIZED_BODY)));
    }

}
