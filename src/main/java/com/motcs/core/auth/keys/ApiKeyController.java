package com.motcs.core.auth.keys;

import com.motcs.commons.annotation.RestServerException;
import com.motcs.commons.utils.Utils;
import com.motcs.core.knowledge.graph.GraphRagRequest;
import com.motcs.core.knowledge.graph.GraphRagService;
import com.motcs.core.knowledge.record.session.ChatSessionRequest;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
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
@Log4j2
@RestController
@RequestMapping("/keys/v1")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final GraphRagService graphRagService;

    @GetMapping
    @Operation(summary = "会话列表")
    public Mono<ResponseEntity<?>> listSessions(ServerWebExchange exchange, ChatSessionRequest request, Pageable pageable) {
        if (ObjectUtils.isEmpty(request.getUserId())) {
            return Mono.error(RestServerException.withMsg("查询会话必须传用户编码！"));
        }
        return this.apiKeyService.resolveApiKey(exchange).flatMap(apiKey -> {
            request.setApiKeyId(apiKey.getId());
            request.setTenantCode(apiKey.getTenantCode());
            if (ObjectUtils.isEmpty(request.getSystemType())) {
                request.setSystemType(apiKey.getSystemType());
            }
            return this.graphRagService.getSessions(request, pageable)
                    .<ResponseEntity<?>>map(ResponseEntity::ok);
        }).switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Utils.UNAUTHORIZED_BODY)));
    }

    @GetMapping("/session")
    @Operation(summary = "按会话ID查询消息（仅本 Key 创建的；不属于该 Key 返回空列表）")
    public Mono<ResponseEntity<?>> sessionMessages(ServerWebExchange exchange, @RequestParam("sessionId") String sessionId) {
        return this.apiKeyService.resolveApiKey(exchange).flatMap(apiKey -> this.graphRagService
                        .querySession(sessionId, apiKey.getId()).<ResponseEntity<?>>map(ResponseEntity::ok))
                .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Utils.UNAUTHORIZED_BODY)));
    }

    /**
     * 手动保存对话记录（前端中断回答时调用，确保部分回答入库）
     * POST /documents/v1/conversations
     */
    @PostMapping("/conversations")
    public Mono<ResponseEntity<Map<String, Object>>> saveConversation(ServerWebExchange exchange, @RequestBody GraphRagRequest request) {
        return this.apiKeyService.resolveApiKey(exchange).<ResponseEntity<Map<String, Object>>>flatMap(apiKey -> {
            request.setApiKeyId(apiKey.getId());
            request.setTenantCode(apiKey.getTenantCode());
            if (ObjectUtils.isEmpty(request.getSystemType())) {
                request.setSystemType(apiKey.getSystemType());
            }
            return this.graphRagService.saveConversation(request)
                    .then(Mono.fromCallable(() -> ResponseEntity.ok(Map.of("success", true))));
        }).switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Utils.UNAUTHORIZED_BODY)));
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
                })).switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Utils.UNAUTHORIZED_BODY)));
    }

    @DeleteMapping("/batch")
    @Operation(summary = "批量删除（Body: sessionId 数组；仅删除本 Key 创建的会话，返回实际删除数）")
    public Mono<ResponseEntity<Map<String, Object>>> deleteBatch(ServerWebExchange exchange, @RequestBody List<String> sessionIds) {
        if (ObjectUtils.isEmpty(sessionIds)) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "sessionIds 不能为空")));
        }
        return this.apiKeyService.resolveApiKey(exchange)
                .flatMap(apiKey -> this.graphRagService.deleteSessions(sessionIds, apiKey.getId())
                        .map(n -> ResponseEntity.ok(Map.<String, Object>of("success", true, "deleted", n))))
                .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Utils.UNAUTHORIZED_BODY)));
    }

}
