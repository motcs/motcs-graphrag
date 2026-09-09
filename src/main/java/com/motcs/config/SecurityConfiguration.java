package com.motcs.config;

import com.motcs.core.auth.csrf.LoginIssuedCsrfTokenRepository;
import com.motcs.core.auth.keys.ApiKeyService;
import com.motcs.core.auth.token.TokenStore;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.*;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 安全配置（WebFlux + Spring Security）
 * <p>
 * 认证途径（二选一即可访问业务接口）：
 * 1. 超管登录（HTTP Basic Auth + Token）：POST /auth/v1/login 携带
 * Authorization: Basic base64(username:password)，认证成功后签发 x-token 返回给前端；
 * 后续所有请求携带请求头 x-token，由 Token 过滤器校验并恢复登录态（ROLE_ADMIN）。
 * 2. API Key：请求头 Authorization: Bearer sk-... 或 X-API-Key: sk-...，
 * 无状态校验（每次请求比对 SHA-256 哈希）。
 * <p>
 * 接口权限：
 * - 静态资源 / 登录接口 / health：公开
 * - /auth/v1/**（API Key 管理）：仅超管登录（ROLE_ADMIN）
 * - /documents/v1/query（AI 对话）：登录态 或 有效 API Key 均可访问
 * - 其余所有业务接口：仅超管登录（ROLE_ADMIN）可访问
 */
@Getter
@Configuration
public class SecurityConfiguration {

    @Value("${app.auth.username:admin}")
    private String adminUsername;

    @Value("${app.auth.password:admin123}")
    private String adminPassword;

    private static final String X_API_KEY = "x-api-Key";
    public static final String X_TOKEN = "x-token";
    /**
     * tokenAuthWebFilter 认证成功标记（exchange attribute），apiKeyWebFilter 据此不覆盖登录
     */
    private static final String AUTH_BY_TOKEN_ATTR = "motcs.auth.byToken";

    /**
     * SecurityContext 存储：No-Op。
     * 采用 x-token 无会话模式（登录态由 TokenStore 管理），
     * 不写入/不读取 WebSession，避免依赖 Cookie。
     */
    @Bean
    public ServerSecurityContextRepository securityContextRepository() {
        return new ServerSecurityContextRepository() {
            @Override
            public @NonNull Mono<Void> save(@NonNull ServerWebExchange exchange, SecurityContext context) {
                return Mono.empty();
            }

            @Override
            public @NonNull Mono<SecurityContext> load(@NonNull ServerWebExchange exchange) {
                return Mono.empty();
            }
        };
    }

    /**
     * 超管账号来源：配置 app.auth.username / app.auth.password（唯一超管，无权限控制）
     */
    @Bean
    public ReactiveUserDetailsService reactiveUserDetailsService() {
        UserDetails admin = User.withUsername(adminUsername)
                .password("{noop}" + adminPassword)
                .roles("ADMIN")
                .build();
        return new MapReactiveUserDetailsService(admin);
    }

    /**
     * 表单登录认证管理器（标准 UsernamePasswordAuthenticationToken 认证）
     */
    @Bean
    public ReactiveAuthenticationManager reactiveAuthenticationManager(ReactiveUserDetailsService userDetailsService) {
        return new UserDetailsRepositoryReactiveAuthenticationManager(userDetailsService);
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ApiKeyService apiKeyService,
                                                         TokenStore tokenStore) {
        return http
                // CSRF 防护：登录后的 POST/PUT/DELETE 需携带 X-CSRF-TOKEN 头（token 与 x-token 关联）。
                // 豁免：安全方法、登录接口（Basic 换 token）、API Key 请求（Bearer / X-API-Key）
                .csrf(csrf -> csrf
                        // CSRF token 通过响应头 Set-Cookie: XSRF-TOKEN 下发（httpOnly=false，前端 JS 可读），
                        // 前端读取后放入请求头 X-CSRF-TOKEN，后端比对 cookie 与 header（双提交模式）
                        // 默认校验头是 X-XSRF-TOKEN，必须显式改为前端使用的 X-CSRF-TOKEN
                        .csrfTokenRepository(customCsrfTokenRepository())
                        // 使用非 XOR 的请求处理器：双提交 cookie 模式下请求头 X-CSRF-TOKEN
                        // 直接等于 cookie 值（默认 XorServerCsrfTokenRequestAttributeHandler
                        // 期望 XOR 编码值，会导致"cookie==header 仍 403"）
                        .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler())
                        // CSRF 校验失败返回 JSON 403（默认是 text/plain "Access Denied"）
                        .accessDeniedHandler((exchange, _) -> writeJson(exchange,
                                HttpStatus.FORBIDDEN, "CSRF 校验失败"))
                        // CSRF 仅对 POST 校验，GET 等其它方法一律不校验；
                        // 豁免：登录接口（Basic 换 token）、API Key 请求（Bearer / X-API-Key）
                        .requireCsrfProtectionMatcher(exchange -> {
                            if (exchange.getRequest().getMethod() != HttpMethod.POST) {
                                return ServerWebExchangeMatcher.MatchResult.notMatch();
                            }
                            // 登录接口豁免（Basic 认证换取 token，尚无 CSRF token）
                            if ("/auth/v1/login".equals(exchange.getRequest().getPath().value())) {
                                return ServerWebExchangeMatcher.MatchResult.notMatch();
                            }
                            // API Key 专属路径整体豁免 CSRF（/keys/v1/** 仅 API Key 访问，
                            // 无 cookie 会话，CSRF 防护无意义；未带 Key 的请求由认证过滤器 401 拦截）
                            if (exchange.getRequest().getPath().value().startsWith("/keys/v1/")) {
                                return ServerWebExchangeMatcher.MatchResult.notMatch();
                            }
                            // API Key 请求免 CSRF：Authorization: Bearer 或 X-API-Key 请求头
                            String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                            if (auth != null && auth.startsWith("Bearer ")) {
                                return ServerWebExchangeMatcher.MatchResult.notMatch();
                            }
                            if (exchange.getRequest().getHeaders().getFirst(X_API_KEY) != null) {
                                return ServerWebExchangeMatcher.MatchResult.notMatch();
                            }
                            // 其余 POST（x-token 登录态）：需要 CSRF 校验
                            return ServerWebExchangeMatcher.MatchResult.match();
                        }))
                // Basic Auth 登录：请求头 Authorization: Basic base64(username:password)。
                // 认证成功后 SecurityContext 保存到 WebSession（会话免登录）并继续进入 Controller
                // （以 Authentication 参数接收登录用户）；认证失败返回 JSON 401 且不带
                // WWW-Authenticate 挑战头（避免浏览器原生 Basic 登录弹窗）
                .httpBasic(basic -> basic
                        .authenticationEntryPoint((exchange, _) -> writeJson(exchange,
                                HttpStatus.UNAUTHORIZED, "用户名或密码错误"))
                        // 认证成功后：保存 WebSession 之外，还必须把认证写入当前请求的 Reactor Context，
                        // 否则后续过滤链/Controller 的 Authentication 参数解析不到登录用户
                        .authenticationSuccessHandler((webFilterExchange, authentication) -> {
                            SecurityContextImpl securityContext = new SecurityContextImpl(authentication);
                            return webFilterExchange.getChain().filter(webFilterExchange.getExchange())
                                    .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                                            Mono.just(securityContext)));
                        }))
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(securityContextRepository())
                // x-token 会话认证过滤器：校验请求头 x-token，通过后恢复登录态（ROLE_ADMIN）
                .addFilterBefore(tokenAuthWebFilter(tokenStore), SecurityWebFiltersOrder.AUTHENTICATION)
                // API Key 无状态认证过滤器：带 Key 的请求独立校验，
                // 通过后把认证信息写入 Reactor Context；不带 Key 的请求放行
                .addFilterBefore(apiKeyWebFilter(apiKeyService), SecurityWebFiltersOrder.AUTHENTICATION)
                // CSRF cookie 滑动过期：每次请求重写同值 cookie 并刷新 30 天有效期（值不变）
                .addFilterAfter(csrfCookieRefreshFilter(), SecurityWebFiltersOrder.CSRF)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/", "/index.html", "/favicon.ico", "/favicon.png",
                                "/css/**", "/js/**", "/img/**",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/webjars/**").permitAll()
                        .pathMatchers("/auth/v1/login").permitAll()
                        .pathMatchers("/documents/v1/health").permitAll()
                        // AI 平台探测（前端登录前即需调用，用于渲染模型下拉框）
                        .pathMatchers("/ai/v1/provider").permitAll()
                        .pathMatchers("/auth/v1/**").hasRole("ADMIN")
                        // AI 对话接口（GraphRAG 问答）：登录 或 有效 API Key 均可访问
                        .pathMatchers("/documents/v1/query").authenticated()
                        // API Key 专属接口（对话历史查询/删除）：仅 API Key 认证可访问，登录用户不可用
                        .pathMatchers("/keys/v1/**").hasRole("API_KEY")
                        // 其余所有业务接口：仅超管登录可访问（API Key 无 ADMIN 角色将被拒绝）
                        .anyExchange().hasRole("ADMIN"))
                // 自定义认证入口/拒绝处理器：返回 JSON，避免浏览器原生 Basic Auth 弹窗
                .exceptionHandling(spec -> spec
                        .authenticationEntryPoint((exchange, _) -> writeJson(exchange,
                                HttpStatus.UNAUTHORIZED, "未登录或登录已过期"))
                        .accessDeniedHandler((exchange, _) -> writeJson(exchange,
                                HttpStatus.FORBIDDEN, "无权限访问")))
                .build();
    }

    /**
     * 以 JSON 形式直接写入响应，不携带 WWW-Authenticate 挑战头（屏蔽浏览器原生登录弹窗）
     */
    private Mono<Void> writeJson(ServerWebExchange exchange, HttpStatus status, String message) {
        // 响应已提交（如 CSRF cookie 首次写入后授权被拒）时，响应头变为只读，
        // setStatusCode/setContentType 会抛 ReadOnlyHttpHeaders 异常导致连接被粗暴关闭；
        // 此时直接放弃写 JSON，保持响应原样结束
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":" + status.value() + ",\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /**
     * CSRF 双提交 Cookie 仓库：token 经 Set-Cookie: XSRF-TOKEN 下发（httpOnly=false 供 JS 读取），
     * 校验头显式设为 X-CSRF-TOKEN（与前端一致，默认值是 X-XSRF-TOKEN）
     */
    private LoginIssuedCsrfTokenRepository customCsrfTokenRepository() {
        // 只读型 CSRF Cookie：cookie 仅由登录接口下发，CSRF 过滤器只读不写，
        // cookie 值始终保持登录时的值
        return new LoginIssuedCsrfTokenRepository();
    }

    /**
     * CSRF cookie 滑动过期：只要请求携带了 XSRF-TOKEN cookie，就在响应中重写
     * 同值 cookie 并刷新有效期（30 天）。值保持登录时的值不变，仅延长过期时间；
     * 持续活跃的用户 cookie 永不过期，超过 30 天不活跃才会失效（POST 403 后重新登录）。
     */
    private WebFilter csrfCookieRefreshFilter() {
        return (exchange, chain) -> {
            HttpCookie csrf = exchange.getRequest().getCookies()
                    .getFirst(LoginIssuedCsrfTokenRepository.CSRF_COOKIE_NAME);
            if (csrf != null && !csrf.getValue().isBlank()) {
                exchange.getResponse().addCookie(ResponseCookie.from(LoginIssuedCsrfTokenRepository
                                .CSRF_COOKIE_NAME, csrf.getValue()).path("/").httpOnly(false).sameSite("Lax")
                        .maxAge(Duration.ofDays(30)).build());
            }
            return chain.filter(exchange);
        };
    }

    /**
     * x-token 会话认证过滤器：从请求头 x-token 解析登录令牌，校验有效后
     * 把认证信息（ROLE_ADMIN）写入当前请求的 Reactor Context。
     * 未携带或无效时直接放行，由 authorizeExchange 决定 401/403；
     * 已有有效认证（如登录请求刚完成 Basic 认证）时不覆盖。
     */
    private WebFilter tokenAuthWebFilter(TokenStore tokenStore) {
        return (exchange, chain) -> {
            String token = exchange.getRequest().getHeaders().getFirst(X_TOKEN);
            if (token == null || token.isBlank()) {
                return chain.filter(exchange);
            }
            // 注意：chain.filter 返回 Mono<Void>（完成时无元素），若在 flatMap 之后直接接
            // switchIfEmpty，会因 empty 误触发而把下游整条链"无认证地"再执行一次。
            // 因此认证分支末尾加 .then(Mono.just(true)) 使其非 empty，switchIfEmpty
            // 只在 token 无效（getUsername 为空）时触发，保证单次执行。
            //
            // Reactor Context 中 SecurityContext key 的值必须是 Mono<SecurityContext>
            // （ReactiveSecurityContextHolder.withSecurityContext 契约），不能放直接对象，
            // 否则下游 cast 抛 ClassCastException。
            // Optional + defaultIfEmpty：使上游恒有值（非 empty），flatMap 必定执行且只执行一次；
            // 避免 switchIfEmpty 因 chain.filter 的 Mono<Void> empty 误触发导致下游链被
            // "无认证地"二次执行（403 覆盖正常响应）。
            return tokenStore.getUsername(token)
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .flatMap(opt -> {
                        if (opt.isEmpty()) {
                            // token 无效：无认证放行（由 authorizeExchange 决定 401/403）
                            return chain.filter(exchange);
                        }
                        var authentication = new UsernamePasswordAuthenticationToken(
                                opt.get(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                        exchange.getAttributes().put(AUTH_BY_TOKEN_ATTR, Boolean.TRUE);
                        return chain.filter(exchange)
                                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                                        Mono.just(new SecurityContextImpl(authentication))));
                    });
        };
    }

    /**
     * API Key 无状态认证过滤器（自定义 WebFilter，完全可控）：
     * 从请求头提取 Key，校验成功后把认证信息（ROLE_API_KEY）写入当前请求的 Reactor Context。
     * 未携带 Key 或校验失败时直接放行，由 authorizeExchange 决定 401/403，
     * 从而避免 AuthenticationWebFilter 回退到默认 ProviderManager 抛 "No provider found"。
     */
    private WebFilter apiKeyWebFilter(ApiKeyService apiKeyService) {
        return (exchange, chain) -> {
            // 登录接口仅走 Basic Auth 认证，API Key 不参与，
            // 避免请求同时携带 Key 时把 Basic 登录用户覆盖为 api-key
            if ("/auth/v1/login".equals(exchange.getRequest().getPath().value())) {
                return chain.filter(exchange);
            }
            String key = extractApiKey(exchange);
            if (key == null) {
                return chain.filter(exchange);
            }
            // 同 tokenAuthWebFilter：认证分支末尾 .then(Mono.just(true)) 避免 switchIfEmpty
            // 误触发双执行；context 值放 Mono<SecurityContext>（withSecurityContext 契约）。
            // 同 tokenAuthWebFilter：Optional + defaultIfEmpty，保证单次执行、类型 Mono<Void>
            return apiKeyService.findEnabled(key)
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .flatMap(opt -> {
                        if (opt.isEmpty()) {
                            // Key 无效：无认证放行
                            return chain.filter(exchange);
                        }
                        // 已由 x-token 认证（tokenAuthWebFilter 先执行并标记）时，
                        // API Key 不覆盖，保持登录身份（会话优先）
                        if (Boolean.TRUE.equals(exchange.getAttribute(AUTH_BY_TOKEN_ATTR))) {
                            return chain.filter(exchange);
                        }
                        var authentication = new UsernamePasswordAuthenticationToken(
                                "api-key", null, List.of(new SimpleGrantedAuthority("ROLE_API_KEY")));
                        // details 携带 ApiKey 实体：下游接口据此识别对话归属（apiKeyId / keyPrefix）
                        authentication.setDetails(opt.get());
                        return chain.filter(exchange)
                                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                                        Mono.just(new SecurityContextImpl(authentication))));
                    });
        };
    }

    public static String extractApiKey(ServerWebExchange exchange) {
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
