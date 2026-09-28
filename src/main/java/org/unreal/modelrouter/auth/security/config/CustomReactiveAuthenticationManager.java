package org.unreal.modelrouter.auth.security.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.unreal.modelrouter.common.exception.SecurityAuthenticationException;
import org.unreal.modelrouter.auth.security.authentication.JwtTokenValidator;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.cache.impl.PlatformAuthCache;
import org.unreal.modelrouter.auth.security.model.ApiKeyAuthentication;
import org.unreal.modelrouter.auth.security.model.JwtAuthentication;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.auth.teacher.model.TeacherAccessAuthentication;
import org.unreal.modelrouter.auth.teacher.service.TeacherAccessService;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.auth.campus.key.CampusGatewayKeyService;
import org.unreal.modelrouter.platform.sync.PlatformDataSyncService;
import reactor.core.publisher.Mono;

/**
 * 自定义响应式认证管理器
 * 处理API Key和JWT令牌认证
 */
@Slf4j
public class CustomReactiveAuthenticationManager implements ReactiveAuthenticationManager {

    private final ApiKeyService apiKeyService;
    private final JwtTokenValidator jwtTokenValidator;
    private final SecurityProperties securityProperties;
    private final PlatformDataSyncService platformDataSyncService;
    private final PlatformAuthCache platformAuthCache;
    private final TeacherAccessService teacherAccessService;
    private final CampusGatewayKeyService campusGatewayKeyService;

    public CustomReactiveAuthenticationManager(ApiKeyService apiKeyService,
                                                JwtTokenValidator jwtTokenValidator,
                                                SecurityProperties securityProperties) {
        this(apiKeyService, jwtTokenValidator, securityProperties, null, null, null);
    }

    public CustomReactiveAuthenticationManager(ApiKeyService apiKeyService,
                                                JwtTokenValidator jwtTokenValidator,
                                                SecurityProperties securityProperties,
                                                PlatformDataSyncService platformDataSyncService) {
        this(apiKeyService, jwtTokenValidator, securityProperties, platformDataSyncService, null, null);
    }

    public CustomReactiveAuthenticationManager(ApiKeyService apiKeyService,
                                                JwtTokenValidator jwtTokenValidator,
                                                SecurityProperties securityProperties,
                                                PlatformDataSyncService platformDataSyncService,
                                                PlatformAuthCache platformAuthCache) {
        this(apiKeyService, jwtTokenValidator, securityProperties,
                platformDataSyncService, platformAuthCache, null);
    }

    public CustomReactiveAuthenticationManager(ApiKeyService apiKeyService,
                                                JwtTokenValidator jwtTokenValidator,
                                                SecurityProperties securityProperties,
                                                PlatformDataSyncService platformDataSyncService,
                                                PlatformAuthCache platformAuthCache,
                                                TeacherAccessService teacherAccessService) {
        this(apiKeyService, jwtTokenValidator, securityProperties, platformDataSyncService,
                platformAuthCache, teacherAccessService, null);
    }

    public CustomReactiveAuthenticationManager(ApiKeyService apiKeyService,
                                                JwtTokenValidator jwtTokenValidator,
                                                SecurityProperties securityProperties,
                                                PlatformDataSyncService platformDataSyncService,
                                                PlatformAuthCache platformAuthCache,
                                                TeacherAccessService teacherAccessService,
                                                CampusGatewayKeyService campusGatewayKeyService) {
        this.campusGatewayKeyService = campusGatewayKeyService;
        this.apiKeyService = apiKeyService;
        this.jwtTokenValidator = jwtTokenValidator;
        this.securityProperties = securityProperties;
        this.platformDataSyncService = platformDataSyncService;
        this.platformAuthCache = platformAuthCache;
        this.teacherAccessService = teacherAccessService;
    }
    
    @Override
    public Mono<Authentication> authenticate(final Authentication authentication) throws AuthenticationException {
        log.debug("开始认证，认证类型: {}", authentication.getClass().getSimpleName());
        
        if (authentication instanceof TeacherAccessAuthentication) {
            return authenticateTeacherAccess((TeacherAccessAuthentication) authentication);
        }
        // 如果是API Key认证
        else if (authentication instanceof ApiKeyAuthentication) {
            return authenticateApiKey((ApiKeyAuthentication) authentication);
        } 
        // 如果是JWT认证
        else if (authentication instanceof JwtAuthentication) {
            return authenticateJwt((JwtAuthentication) authentication);
        }
        
        // 如果都不是，返回认证失败
        log.warn("不支持的认证类型: {}", authentication.getClass().getSimpleName());
        return Mono.error(new SecurityAuthenticationException(
                "UNSUPPORTED_AUTH_TYPE", 
                "不支持的认证类型: " + authentication.getClass().getSimpleName()
        ));
    }

    private Mono<Authentication> authenticateTeacherAccess(
            final TeacherAccessAuthentication authentication) {
        if (teacherAccessService == null) {
            return Mono.error(new org.unreal.modelrouter.common.exception.AuthenticationException(
                    "教师设备绑定认证未启用", "TEACHER_ACCESS_DISABLED"));
        }
        String token = (String) authentication.getCredentials();
        return Mono.fromCallable(() -> teacherAccessService.authenticate(
                        token, authentication.getRequestProof()))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .map(result -> (Authentication) new TeacherAccessAuthentication(
                        token,
                        authentication.getRequestProof(),
                        result.identity(),
                        result.permissions()
                ));
    }
    
    /**
     * 认证API Key
     */
    private Mono<Authentication> authenticateApiKey(final ApiKeyAuthentication authentication) {
        // 检查API Key功能是否启用
        if (!securityProperties.getApiKey().isEnabled()) {
            log.debug("API Key认证未启用");
            return Mono.error(new SecurityAuthenticationException(
                    "API_KEY_DISABLED",
                    "API Key认证功能未启用"
            ));
        }

        String apiKey = (String) authentication.getCredentials();

        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.debug("API Key为空");
            return Mono.error(new SecurityAuthenticationException(
                    "EMPTY_API_KEY",
                    "API Key不能为空"
            ));
        }

        if (CampusGatewayKeyService.isNewKey(apiKey)) {
            if (campusGatewayKeyService == null || platformDataSyncService == null) {
                return Mono.error(new org.unreal.modelrouter.common.exception.AuthenticationException(
                        "校园 Key 服务不可用", "API_KEY_UNAVAILABLE"));
            }
            return Mono.fromCallable(() -> {
                var key = campusGatewayKeyService.authenticate(apiKey);
                if (key == null) throw new org.unreal.modelrouter.common.exception.AuthenticationException(
                        "无效或已停用的 Key", "API_KEY_INVALID");
                // Refresh the platform's verified billing identity; never trust a stale CAS snapshot.
                var identity = platformDataSyncService.getUserIdentityBySystemUserId(
                        key.ownerId(), key.platformUserId()).orElseThrow(() ->
                        new org.unreal.modelrouter.common.exception.AuthenticationException(
                                "校园账户未关联有效计费身份", "BILLING_IDENTITY_MISSING"));
                if (!key.platformUserId().equals(identity.userId())) {
                    throw new org.unreal.modelrouter.common.exception.AuthenticationException(
                            "Key 所属计费账户已变更", "BILLING_IDENTITY_CHANGED");
                }
                var owned = new UserIdentity(identity.userId(), identity.userAccount(), key.keyId(), key.name(),
                        identity.enterpriseId(), identity.enterpriseName(), identity.companyId(),
                        identity.platformUser(), identity.userType(), identity.systemUserId(), identity.verifyStatus());
                return buildPlatformAuthentication(apiKey, owned);
            }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
        }

        // No legacy local-key validation. Only external platform credentials may use this branch.
        return Mono.<Authentication>error(new org.unreal.modelrouter.common.exception.AuthenticationException(
                "无效的API Key", "API_KEY_INVALID"))
                .onErrorResume(error -> {
                    // 本地校验失败，尝试算力平台 API Key 校验
                    if (platformDataSyncService == null) {
                        return Mono.error(error);
                    }

                    // 1. 先查平台认证缓存
                    if (platformAuthCache != null) {
                        PlatformAuthCache.CacheEntry cached = platformAuthCache.get(apiKey);
                        if (cached != null) {
                            if (cached.identity() != null) {
                                // 正缓存命中
                                log.debug("平台认证缓存命中(正): {}...", apiKey.substring(0, Math.min(8, apiKey.length())));
                                return Mono.just(buildPlatformAuthentication(apiKey, cached.identity()));
                            } else {
                                // 负缓存命中：key 已知无效（不存在）
                                log.debug("平台认证缓存命中(负): {}...", apiKey.substring(0, Math.min(8, apiKey.length())));
                                return Mono.error(new org.unreal.modelrouter.common.exception.AuthenticationException(
                                        "无效的API Key", "API_KEY_INVALID"));
                            }
                        }
                    }

                    // 2. 缓存未命中 → 单次 DB 查询（区分未找到 / 已过期 / 有效）
                    return Mono.fromCallable(() -> platformDataSyncService.lookupApiKey(apiKey))
                            .flatMap(lookupResult -> {
                                if (lookupResult.expired()) {
                                    // key 存在但已过期 → 不缓存（过期状态可能被外部更新）
                                    log.debug("算力平台API Key已过期: expireTime={}", lookupResult.expireTime());
                                    return Mono.error(new org.unreal.modelrouter.common.exception.AuthenticationException(
                                            "API Key已过期，过期时间: " + lookupResult.expireTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), "API_KEY_EXPIRED"));
                                }
                                if (lookupResult.identity() != null) {
                                    // key 有效 → 写正缓存（携带 key 过期时间，防止缓存时间超过 key 有效期）
                                    log.debug("算力平台API Key认证成功: {}...", apiKey.substring(0, Math.min(8, apiKey.length())));
                                    if (platformAuthCache != null) {
                                        platformAuthCache.putValid(apiKey, lookupResult.identity(), lookupResult.expireTime());
                                    }
                                    return Mono.just(buildPlatformAuthentication(apiKey, lookupResult.identity()));
                                }
                                // key 不存在 → 写负缓存
                                log.debug("算力平台API Key认证失败(未找到匹配key)");
                                if (platformAuthCache != null) {
                                    platformAuthCache.putInvalid(apiKey);
                                }
                                return Mono.error(new org.unreal.modelrouter.common.exception.AuthenticationException(
                                        "无效的API Key", "API_KEY_INVALID"));
                            });
                })
                .onErrorMap(throwable -> {
                    if (throwable instanceof org.unreal.modelrouter.common.exception.AuthenticationException) {
                        return throwable;
                    }
                    return new org.unreal.modelrouter.common.exception.AuthenticationException(
                            "API Key认证失败: " + throwable.getMessage(),
                            "API_KEY_AUTH_FAILED"
                    );
                });
    }

    /**
     * 构建算力平台 API Key 认证成功的 Authentication 对象
     */
    private Authentication buildPlatformAuthentication(String apiKey, UserIdentity identity) {
        ApiKeyAuthentication authenticated = new ApiKeyAuthentication(
                identity.apiKeyId(), apiKey,
                java.util.List.of("USER", "chat", "embedding", "rerank", "tts", "stt", "imgGen", "imgEdit", "vidGen")
        );
        authenticated.setAuthenticated(true);
        authenticated.setDetails(identity);
        return authenticated;
    }
    
    /**
     * 认证JWT令牌
     */
    private Mono<Authentication> authenticateJwt(final JwtAuthentication authentication) {
        // 检查JWT功能是否启用
        if (!securityProperties.getJwt().isEnabled()) {
            log.debug("JWT认证未启用");
            return Mono.error(new SecurityAuthenticationException(
                    "JWT_DISABLED", 
                    "JWT认证功能未启用"
            ));
        }
        
        String token = (String) authentication.getCredentials();
        
        if (token == null || token.trim().isEmpty()) {
            log.debug("JWT令牌为空");
            return Mono.error(new SecurityAuthenticationException(
                    "EMPTY_JWT_TOKEN", 
                    "JWT令牌不能为空"
            ));
        }
        
        return jwtTokenValidator.validateToken(token)
                .doOnNext(validatedAuth -> log.debug("JWT令牌认证成功: {}", validatedAuth.getName()))
                .doOnError(error -> log.debug("JWT令牌认证失败: {}", error.getMessage()))
                .onErrorMap(throwable -> {
                    if (throwable instanceof org.unreal.modelrouter.common.exception.AuthenticationException) {
                        return throwable;
                    }
                    
                    // 检查是否是JWT过期相关的错误
                    String message = throwable.getMessage();
                    if (message != null && message.contains("expired")) {
                        return new org.unreal.modelrouter.common.exception.AuthenticationException(
                                "JWT令牌已过期，请重新获取",
                                "EXPIRED_JWT_TOKEN"
                        );
                    }
                    
                    return new org.unreal.modelrouter.common.exception.AuthenticationException(
                            "JWT令牌认证失败: " + throwable.getMessage(),
                            "JWT_AUTH_FAILED"
                    );
                });
    }
}
