package org.unreal.modelrouter.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.unreal.modelrouter.common.exception.AuthenticationException;
import org.unreal.modelrouter.common.exception.SecurityAuthenticationException;
import org.unreal.modelrouter.auth.security.config.ExcludedPathsConfig;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import org.unreal.modelrouter.auth.security.model.ApiKeyAuthentication;
import org.unreal.modelrouter.auth.security.model.JwtAuthentication;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.auth.security.model.JwtPrincipal;
import org.unreal.modelrouter.auth.teacher.model.TeacherAccessAuthentication;
import org.unreal.modelrouter.auth.campus.model.CampusAuthentication;
import org.unreal.modelrouter.auth.teacher.config.TeacherAccessProperties;
import org.unreal.modelrouter.billing.freequota.FreeQuotaService;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * Spring Security集成的认证过滤器
 * 只处理认证相关异常，其他业务异常交由全局异常处理器处理
 */
@Slf4j
public class SpringSecurityAuthenticationFilter implements WebFilter {

    private final SecurityProperties securityProperties;
    private final ServerAuthenticationConverter authenticationConverter;
    private final ReactiveAuthenticationManager authenticationManager;
    private final FreeQuotaService freeQuotaService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SpringSecurityAuthenticationFilter(
            final SecurityProperties securityProperties,
            final ServerAuthenticationConverter authenticationConverter,
            final ReactiveAuthenticationManager authenticationManager) {
        this(securityProperties, authenticationConverter, authenticationManager,
                null, new TeacherAccessProperties());
    }

    public SpringSecurityAuthenticationFilter(
            final SecurityProperties securityProperties,
            final ServerAuthenticationConverter authenticationConverter,
            final ReactiveAuthenticationManager authenticationManager,
            final FreeQuotaService freeQuotaService) {
        this(securityProperties, authenticationConverter, authenticationManager,
                freeQuotaService, new TeacherAccessProperties());
    }

    public SpringSecurityAuthenticationFilter(
            final SecurityProperties securityProperties,
            final ServerAuthenticationConverter authenticationConverter,
            final ReactiveAuthenticationManager authenticationManager,
            final FreeQuotaService freeQuotaService,
            final TeacherAccessProperties teacherAccessProperties) {
        this.securityProperties = securityProperties;
        this.authenticationConverter = authenticationConverter;
        this.authenticationManager = authenticationManager;
        this.freeQuotaService = freeQuotaService;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
        // 对 CORS 预检请求（OPTIONS）直接放行，不执行认证
        if (exchange.getRequest().getMethod() == org.springframework.http.HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }

        // CAS login restores its authentication from the WebSession before this filter.
        // Reuse it instead of incorrectly demanding a second API key/JWT credential.
        // ReactorContextWebFilter loads the saved session before AUTHENTICATION,
        // but the exchange principal wrapper is installed later in the chain.
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(org.springframework.security.core.context.SecurityContext::getAuthentication)
                .switchIfEmpty(exchange.getPrincipal().ofType(Authentication.class))
                .filter(authentication -> authentication.isAuthenticated()
                        && !"anonymousUser".equals(authentication.getPrincipal()))
                // A successful WebFilterChain is Mono<Void>; use a marker so its empty
                // completion cannot trigger the missing-credential fallback a second time.
                .flatMap(authentication -> continueWithSecurityContext(authentication, exchange, chain)
                        .thenReturn(Boolean.TRUE))
                .switchIfEmpty(Mono.defer(() -> requiresAuthentication(exchange)
                .flatMap(authRequired -> {
                    if (!authRequired) {
                        return chain.filter(exchange);
                    }

                    // 如果API Key和JWT都未启用，则跳过认证
                    if (!securityProperties.getApiKey().isEnabled()
                            && !securityProperties.getJwt().isEnabled()
                            && !hasTeacherAccessToken(exchange)) {
                        return chain.filter(exchange);
                    }

                    // 对于multipart请求，使用特殊的处理逻辑
                    if (isMultipartRequest(exchange)) {
                        log.debug("检测到multipart请求，使用特殊处理逻辑: {}", exchange.getRequest().getPath().value());
                        return handleMultipartAuthentication(exchange, chain);
                    }

                    // 转换请求为认证对象并执行认证
                    return performAuthentication(exchange, chain);
                }).thenReturn(Boolean.TRUE)))
                .then();
    }

    /**
     * 执行实际的认证逻辑
     */
    private Mono<Void> performAuthentication(final ServerWebExchange exchange, final WebFilterChain chain) {
        return authenticationConverter.convert(exchange)
                .flatMap(authenticationManager::authenticate)
                .flatMap(authenticated -> continueWithSecurityContext(authenticated, exchange, chain)
                        .thenReturn(Boolean.TRUE))
                .switchIfEmpty(Mono.defer(() -> {
                    // 没有提供认证信息，返回401错误
                    log.warn("请求缺少认证信息: {}", exchange.getRequest().getPath().value());
                    return createAuthenticationErrorResponse(exchange,
                            "请求缺少认证信息，请提供API Key或JWT Token",
                            "AUTH_MISSING").thenReturn(Boolean.TRUE);
                }))
                .then()
                // 只捕获认证相关异常，其它异常放行
                .onErrorResume(throwable -> {
                    if (isAuthException(throwable)) {
                        return handleAuthenticationError(exchange, throwable);
                    }
                    return Mono.error(throwable);
                });
    }

    /**
     * 检查是否应该进行认证
     */
    private Mono<Boolean> requiresAuthentication(final ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        // 使用ExcludedPathsConfig.AUTH_EXCLUDED_PATHS判断是否需要认证
        boolean isExcluded = ExcludedPathsConfig.isAuthExcluded(path);
        log.debug("=== 认证检查: path={}, isExcluded={}, requiresAuth={} ===", path, isExcluded, !isExcluded);
        // 如果路径不在排除列表中，则需要认证
        return Mono.just(!isExcluded);
    }

    /**
     * 检查是否为multipart请求
     */
    private boolean isMultipartRequest(final ServerWebExchange exchange) {
        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        return contentType != null && contentType.isCompatibleWith(MediaType.MULTIPART_FORM_DATA);
    }

    private boolean hasTeacherAccessToken(final ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        return authorization != null
                && authorization.startsWith("Bearer " + TeacherAccessProperties.TOKEN_PREFIX);
    }

    /**
     * 处理multipart请求的认证
     * 只捕获认证相关异常，其它异常放行
     */
    private Mono<Void> handleMultipartAuthentication(final ServerWebExchange exchange, final WebFilterChain chain) {
        log.debug("开始处理multipart请求认证: {}", exchange.getRequest().getPath().value());

        // 对于multipart请求，直接从请求头中提取认证信息，避免读取请求体
        return authenticationConverter.convert(exchange)
                .flatMap(authentication -> {
                    if (authentication == null) {
                        return handleMissingAuthentication(exchange);
                    }

                    return authenticationManager.authenticate(authentication)
                            .flatMap(authenticated -> continueWithSecurityContext(authenticated, exchange, chain))
                            // 只捕获认证相关异常，其它异常放行
                            .onErrorResume(throwable -> {
                                if (isAuthException(throwable)) {
                                    return handleAuthenticationError(exchange, throwable);
                                }
                                return Mono.error(throwable);
                            });
                })
                .switchIfEmpty(handleMissingAuthentication(exchange))
                // 只捕获认证相关异常，其它异常放行
                .onErrorResume(throwable -> {
                    if (isAuthException(throwable)) {
                        log.error("Multipart请求认证过程中发生认证异常: {}", throwable.getMessage(), throwable);
                        return handleAuthenticationError(exchange, throwable);
                    }
                    log.error("Multipart请求认证过程中发生非认证异常: {}", throwable.getMessage(), throwable);
                    return Mono.error(throwable);
                });
    }

    /**
     * 工具方法：判断是否为认证相关异常
     */
    private boolean isAuthException(final Throwable throwable) {
        return throwable instanceof AuthenticationException
                || throwable instanceof SecurityAuthenticationException;
    }

    /**
     * 在安全上下文中继续执行过滤器链
     * 优化版本：设置上下文后直接继续，不会重复进入认证流程
     */
    private Mono<Void> continueWithSecurityContext(final Authentication authenticated, final ServerWebExchange exchange, final WebFilterChain chain) {
        log.debug("设置认证上下文并继续执行过滤器链: {} - 用户: {}",
                exchange.getRequest().getPath().value(),
                authenticated.getName());

        SecurityContextImpl securityContext = new SecurityContextImpl(authenticated);
        UserIdentity identity = extractUserIdentity(authenticated);
        exchange.getAttributes().put(UserIdentity.CONTEXT_KEY, identity);
        Mono<Void> continuation = chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(securityContext)))
                .contextWrite(Context.of(UserIdentity.CONTEXT_KEY, identity));
        return continuation;
    }

    /**
     * 处理缺少认证信息的情况
     */
    private Mono<Void> handleMissingAuthentication(final ServerWebExchange exchange) {
        log.warn("请求缺少认证信息: {}", exchange.getRequest().getPath().value());
        return createAuthenticationErrorResponse(exchange,
                "请求缺少认证信息，请提供 X-API-Key 或 Authorization: Bearer <token>",
                "AUTH_MISSING");
    }

    /**
     * 处理认证错误
     */
    private Mono<Void> handleAuthenticationError(final ServerWebExchange exchange, final Throwable throwable) {
        log.warn("认证失败: {}", exchange.getRequest().getPath().value(), throwable);

        String message = "认证失败";
        String errorCode = "AUTH_FAILED";

        // 根据具体异常类型提供更具体的错误信息
        if (throwable instanceof AuthenticationException authException) {
            message = authException.getMessage();
            errorCode = authException.getErrorCode();
        } else if (throwable instanceof SecurityAuthenticationException authException) {
            message = authException.getMessage();
            errorCode = authException.getErrorCode();
        } else {
            message = "认证过程中发生未知错误";
            errorCode = "AUTH_ERROR";
        }

        return createAuthenticationErrorResponse(exchange, message, errorCode);
    }

    /**
     * 创建认证错误响应
     */
    private Mono<Void> createAuthenticationErrorResponse(final ServerWebExchange exchange, final String message, final String errorCode) {
        ServerHttpResponse response = exchange.getResponse();

        // 检查响应是否已经提交
        if (response.isCommitted()) {
            log.warn("响应已提交，无法创建认证错误响应");
            return Mono.empty();
        }

        response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        // 使用 ObjectMapper 安全序列化，防止 JSON 注入（换行符、制表符等特殊字符）
        String errorResponse;
        try {
            errorResponse = objectMapper.writeValueAsString(buildAuthErrorBody(exchange, message, errorCode));
        } catch (Exception e) {
            log.warn("序列化认证错误响应失败: {}", e.getMessage());
            errorResponse = "{\"error\":{\"message\":\"Authentication failed\",\"type\":\"authentication_error\",\"code\":\"AUTH_ERROR\"}}";
        }

        return response.writeWith(Mono.just(response.bufferFactory().wrap(errorResponse.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .onErrorResume(throwable -> {
                    log.error("写入认证错误响应时发生异常: {}", throwable.getMessage(), throwable);
                    return Mono.empty();
                });
    }

    /**
     * 按请求路径构造认证错误体：
     *  - /v1/messages（Anthropic 端点）-> {"type":"error","error":{"type":"authentication_error","message"}}
     *  - /v1/chat/completions（OpenAI 端点）-> {"error":{"message","type":"invalid_request_error","code"}}
     *  - 其他路径 -> 保持既有格式 {"error":{"message","type":"authentication_error","code"}}
     */
    private java.util.Map<String, Object> buildAuthErrorBody(final ServerWebExchange exchange,
                                                             final String message, final String errorCode) {
        String path = exchange.getRequest().getPath().value();

        if (path.startsWith("/api/v1/messages") || path.startsWith("/v1/messages")) {
            return java.util.Map.of(
                    "type", "error",
                    "error", java.util.Map.of(
                            "type", "authentication_error",
                            "message", message
                    )
            );
        }
        if (path.startsWith("/api/v1/chat/completions") || path.startsWith("/v1/chat/completions")) {
            return java.util.Map.of(
                    "error", java.util.Map.of(
                            "message", message,
                            "type", "invalid_request_error",
                            "code", errorCode
                    )
            );
        }
        return java.util.Map.of(
                "error", java.util.Map.of(
                        "message", message,
                        "type", "authentication_error",
                        "code", errorCode
                )
        );
    }

    private UserIdentity extractUserIdentity(final Authentication auth) {
        try {
            if (auth instanceof CampusAuthentication campusAuthentication) {
                return campusAuthentication.getPrincipal().userIdentity();
            }
            if (auth instanceof TeacherAccessAuthentication teacherAuthentication
                    && teacherAuthentication.getDetails() instanceof UserIdentity teacherIdentity) {
                return teacherIdentity;
            }
            // 优先：平台认证直接返回了 UserIdentity（含企业信息）
            if (auth instanceof ApiKeyAuthentication apiKeyAuth) {
                Object details = apiKeyAuth.getDetails();
                if (details instanceof UserIdentity platformIdentity) {
                    log.info("平台认证身份: user={}, enterpriseId={}, enterprise={}, companyId={}",
                            platformIdentity.userAccount(), platformIdentity.enterpriseId(),
                            platformIdentity.enterpriseName(), platformIdentity.companyId());
                    return platformIdentity;
                }
                // 本地 API Key 认证
                if (details instanceof org.unreal.modelrouter.auth.security.config.properties.ApiKey apiKey) {
                    String userAccount = apiKey.getCreatedBy() != null ? apiKey.getCreatedBy() : "api_key_user";
                    return new UserIdentity(apiKey.getKeyId(), userAccount, apiKey.getKeyId(), apiKey.getDescription(), null, null, null, false, null, null, null);
                }
                return new UserIdentity((String) apiKeyAuth.getPrincipal(), "api_key_user",
                        (String) apiKeyAuth.getPrincipal(), null, null, null, null, false, null, null, null);
            }

            if (auth instanceof JwtAuthentication jwtAuth) {
                Object details = jwtAuth.getDetails();
                if (details instanceof JwtPrincipal jwtPrincipal) {
                    String userId = jwtPrincipal.getStringClaim("userId");
                    if (userId == null || userId.isEmpty()) {
                        userId = jwtPrincipal.getSubject();
                    }
                    return new UserIdentity(userId, jwtPrincipal.getSubject(), null, null, null, null, null, false, null, null, null);
                }
                return new UserIdentity((String) jwtAuth.getPrincipal(), (String) jwtAuth.getPrincipal(), null, null, null, null, null, false, null, null, null);
            }

            String name = auth.getName();
            if (name != null && !"anonymous".equals(name)) {
                return new UserIdentity(name, name, null, null, null, null, null, false, null, null, null);
            }
        } catch (Exception e) {
            log.warn("提取用户身份失败: {}", e.getMessage());
        }
        return UserIdentity.SYSTEM;
    }
}



