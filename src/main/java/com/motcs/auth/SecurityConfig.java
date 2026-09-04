package com.motcs.auth;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 安全配置（WebFlux + Spring Security）
 * <p>
 * 认证途径（二选一即可访问业务接口）：
 * 1. 超管登录：POST /api/auth/login 校验配置中的账号密码，成功后认证写入 WebSession，
 * 同会话后续请求自动携带登录态（前端 fetch 同源自动带 Cookie）。
 * 2. API Key：请求头 Authorization: Bearer sk-... 或 X-API-Key: sk-...，
 * 无状态校验（每次请求比对 SHA-256 哈希）。
 * <p>
 * 接口权限：
 * - 静态资源 / 登录接口 / health：公开
 * - /api/auth/**（API Key 管理）：仅超管登录（ROLE_ADMIN）
 * - /api/documents/query（AI 对话）：登录态 或 有效 API Key 均可访问
 * - 其余所有业务接口：仅超管登录（ROLE_ADMIN）可访问
 */
@Getter
@Configuration
public class SecurityConfig {

    @Value("${app.auth.username:admin}")
    private String adminUsername;

    @Value("${app.auth.password:admin123}")
    private String adminPassword;

    private static final String X_API_KEY = "x-api-Key";
    /**
     * 登录态 SecurityContext 存储（WebSession）
     */
    @Bean
    public ServerSecurityContextRepository securityContextRepository() {
        return new WebSessionServerSecurityContextRepository();
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ApiKeyService apiKeyService) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(securityContextRepository())
                // API Key 无状态认证过滤器：每个请求独立校验，通过后把认证信息写入 Reactor Context
                .addFilterAt(apiKeyWebFilter(apiKeyService), SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/", "/index.html", "/favicon.ico", "/favicon.png",
                                "/css/**", "/js/**", "/img/**",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/webjars/**").permitAll()
                        .pathMatchers("/api/auth/login").permitAll()
                        .pathMatchers("/api/documents/health").permitAll()
                        .pathMatchers("/api/auth/**").hasRole("ADMIN")
                        // AI 对话接口（GraphRAG 问答）：登录 或 有效 API Key 均可访问
                        .pathMatchers("/api/documents/query").authenticated()
                        // 其余所有业务接口：仅超管登录可访问（API Key 无 ADMIN 角色将被拒绝）
                        .anyExchange().hasRole("ADMIN"))
                // 自定义认证入口/拒绝处理器：返回 JSON，避免浏览器原生 Basic Auth 弹窗
                .exceptionHandling(spec -> spec
                        .authenticationEntryPoint((exchange, ex) -> writeJson(exchange,
                                HttpStatus.UNAUTHORIZED, "未登录或登录已过期"))
                        .accessDeniedHandler((exchange, ex) -> writeJson(exchange,
                                HttpStatus.FORBIDDEN, "无权限访问")))
                .build();
    }

    /**
     * 以 JSON 形式直接写入响应，不携带 WWW-Authenticate 挑战头（屏蔽浏览器原生登录弹窗）
     */
    private Mono<Void> writeJson(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":" + status.value() + ",\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /**
     * API Key 无状态认证过滤器（自定义 WebFilter，完全可控）：
     * 从请求头提取 Key，校验成功后把认证信息（ROLE_API_KEY）写入当前请求的 Reactor Context。
     * 未携带 Key 或校验失败时直接放行，由 authorizeExchange 决定 401/403，
     * 从而避免 AuthenticationWebFilter 回退到默认 ProviderManager 抛 "No provider found"。
     */
    private WebFilter apiKeyWebFilter(ApiKeyService apiKeyService) {
        return (exchange, chain) -> {
            String key = extractApiKey(exchange);
            if (key == null) {
                return chain.filter(exchange);
            }
            return apiKeyService.validate(key).flatMap(valid -> {
                if (!valid) {
                    return chain.filter(exchange);
                }
                var authentication = new UsernamePasswordAuthenticationToken(
                        "api-key", null, List.of(new SimpleGrantedAuthority("ROLE_API_KEY")));
                return chain.filter(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                                Mono.just(new SecurityContextImpl(authentication))));
            });
        };
    }

    private String extractApiKey(ServerWebExchange exchange) {
        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        String xKey = exchange.getRequest().getHeaders().getFirst(X_API_KEY);
        if (xKey != null && !xKey.isBlank()) {
            // 兼容 X-API-Key 值误带 "Bearer " 前缀的情况
            String trimmed = xKey.trim();
            if (trimmed.startsWith("Bearer ")) {
                return trimmed.substring(7).trim();
            }
            return trimmed;
        }
        return null;
    }

}
