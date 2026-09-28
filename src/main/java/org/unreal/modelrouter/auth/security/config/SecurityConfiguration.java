package org.unreal.modelrouter.auth.security.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.unreal.modelrouter.auth.campus.config.CampusAuthProperties;
import org.unreal.modelrouter.common.exceptionhandler.ReactiveGlobalExceptionHandler;
import org.unreal.modelrouter.auth.security.cache.impl.PlatformAuthCache;
import org.unreal.modelrouter.auth.filter.DefaultAuthenticationConverter;
import org.unreal.modelrouter.auth.filter.SpringSecurityAuthenticationFilter;
import org.unreal.modelrouter.auth.security.authentication.JwtTokenValidator;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.monitor.tracing.config.TracingSecurityConfiguration;
import org.unreal.modelrouter.auth.teacher.config.TeacherAccessProperties;
import org.unreal.modelrouter.auth.teacher.security.ClientCertificateFingerprintExtractor;
import org.unreal.modelrouter.auth.teacher.service.TeacherAccessService;
import org.unreal.modelrouter.billing.freequota.FreeQuotaService;

import java.util.List;

/**
 * Spring Security配置类
 * 配置WebFlux安全过滤器链和认证管理器
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "jairouter.security.enabled", havingValue = "true")
public class SecurityConfiguration {

    private final SecurityProperties securityProperties;
    private final ApiKeyService apiKeyService;
    private JwtTokenValidator jwtTokenValidator;

    private final ApplicationContext applicationContext;

    @Autowired(required = false)
    private TracingSecurityConfiguration.TracingSecurityFilterChainCustomizer tracingCustomizer;

    @Autowired(required = false)
    private FreeQuotaService freeQuotaService;

    // 使用setter注入JwtTokenValidator，使其成为可选依赖
    @Autowired(required = false)
    public void setJwtTokenValidator(final JwtTokenValidator jwtTokenValidator) {
        this.jwtTokenValidator = jwtTokenValidator;
    }

    /**
     * 配置安全过滤器链
     * 定义哪些路径需要认证，哪些可以匿名访问，实现基于角色的访问控制（RBAC）
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            final ServerHttpSecurity http,
            final ReactiveAuthenticationManager authenticationManager,
            final ServerAuthenticationConverter serverAuthenticationConverter,
            final TeacherAccessProperties teacherAccessProperties,
            final CampusAuthProperties campusAuthProperties) {
        return buildSecurityWebFilterChain(
                http, authenticationManager, serverAuthenticationConverter,
                teacherAccessProperties, campusAuthProperties);
    }

    /** Backwards-compatible entry point for tests with campus authentication disabled. */
    public SecurityWebFilterChain securityWebFilterChain(
            final ServerHttpSecurity http,
            final ReactiveAuthenticationManager authenticationManager,
            final ServerAuthenticationConverter serverAuthenticationConverter) {
        return buildSecurityWebFilterChain(
                http, authenticationManager, serverAuthenticationConverter,
                new TeacherAccessProperties(), new CampusAuthProperties());
    }

    private SecurityWebFilterChain buildSecurityWebFilterChain(
            final ServerHttpSecurity http,
            final ReactiveAuthenticationManager authenticationManager,
            final ServerAuthenticationConverter serverAuthenticationConverter,
            final TeacherAccessProperties teacherAccessProperties,
            final CampusAuthProperties campusAuthProperties) {

        log.info("配置 Spring Security WebFlux 过滤器链");
        ServerHttpSecurity customizedHttp = tracingCustomizer == null ? http : tracingCustomizer.customize(http);
        ServerSecurityContextRepository securityContextRepository = campusAuthProperties.isEnabled()
                ? new WebSessionServerSecurityContextRepository()
                : NoOpServerSecurityContextRepository.getInstance();

        CookieServerCsrfTokenRepository csrfRepository = CookieServerCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> cookie.path("/").secure(campusAuthProperties.isCookieSecure()).sameSite("Lax"));

        ServerHttpSecurity.AuthorizeExchangeSpec authorizeExchangeSpec = customizedHttp
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .requireCsrfProtectionMatcher(this::requiresSessionCsrf))
                .cors(cors -> cors.disable())
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(securityContextRepository)
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((exchange, ex) -> {
                    ReactiveGlobalExceptionHandler handler = applicationContext.getBean(ReactiveGlobalExceptionHandler.class);
                    return handler.handle(exchange, ex);
                }))
                .authorizeExchange();

        authorizeExchangeSpec
                .pathMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                .pathMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                .pathMatchers("/swagger-ui/**", "/v3/api-docs/**", "/webjars/**").permitAll()
                .pathMatchers("/favicon.ico").permitAll()
                .pathMatchers("/admin/**").permitAll()
                .pathMatchers(org.springframework.http.HttpMethod.GET,
                        "/api/auth/cas/login", "/api/auth/cas/callback").permitAll()
                .pathMatchers(org.springframework.http.HttpMethod.POST,
                        "/api/auth/cas/slo", "/api/auth/cas/callback").permitAll()
                .pathMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/jwt/login").permitAll()
                .pathMatchers(org.springframework.http.HttpMethod.POST,
                        "/api/auth/jwt/validate", "/api/auth/jwt/refresh").permitAll()
                .pathMatchers(org.springframework.http.HttpMethod.GET,
                        "/v1/models", "/v1/models/**", "/api/v1/models", "/api/v1/models/**").permitAll()
                .pathMatchers("/api/auth/session", "/api/auth/cas/logout").authenticated();

        authorizeExchangeSpec.pathMatchers("/api/teacher/**").hasRole("TEACHER");

        authorizeExchangeSpec
                .pathMatchers("/api/admin/**", "/api/security/**", "/api/config/**", "/internal/**")
                    .hasRole("ADMIN")
                .pathMatchers("/v1/**", "/api/v1/**")
                    .hasAnyAuthority("ROLE_READ", "ROLE_WRITE", "ROLE_USER", "ROLE_ADMIN",
                            "ROLE_TEACHER", "ROLE_STUDENT")
                .pathMatchers("/api/me/**", "/api/models", "/api/model-square", "/api/model-square/**").authenticated()
                .pathMatchers("/api/**").hasRole("ADMIN")
                .pathMatchers("/actuator/**").hasRole("ADMIN")
                .anyExchange().authenticated();

        SpringSecurityAuthenticationFilter securityFilter = new SpringSecurityAuthenticationFilter(
                securityProperties, serverAuthenticationConverter, authenticationManager,
                freeQuotaService, teacherAccessProperties);
        return customizedHttp
                .addFilterBefore(securityFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    private reactor.core.publisher.Mono<ServerWebExchangeMatcher.MatchResult> requiresSessionCsrf(
            final org.springframework.web.server.ServerWebExchange exchange) {
        org.springframework.http.HttpMethod method = exchange.getRequest().getMethod();
        boolean safe = method == null || method == org.springframework.http.HttpMethod.GET
                || method == org.springframework.http.HttpMethod.HEAD
                || method == org.springframework.http.HttpMethod.OPTIONS
                || method == org.springframework.http.HttpMethod.TRACE;
        String path = exchange.getRequest().getPath().value();
        boolean explicitlyExempt = path.equals("/api/auth/jwt/login")
                || path.equals("/api/auth/jwt/refresh")
                || path.equals("/api/auth/jwt/validate")
                || path.equals("/api/auth/cas/slo")
                || (path.equals("/api/auth/cas/callback")
                        && method == org.springframework.http.HttpMethod.POST);
        org.springframework.http.HttpHeaders headers = exchange.getRequest().getHeaders();
        boolean statelessCredential = headers.containsKey("X-API-Key")
                || headers.containsKey("Jairouter_Token")
                || headers.containsKey(org.springframework.http.HttpHeaders.AUTHORIZATION);
        return (safe || explicitlyExempt || statelessCredential)
                ? ServerWebExchangeMatcher.MatchResult.notMatch()
                : ServerWebExchangeMatcher.MatchResult.match();
    }


    /**
     * 配置响应式认证管理器
     * 处理不同类型的认证请求
     */
    @Bean
    public ReactiveAuthenticationManager reactiveAuthenticationManager(
            final ObjectProvider<org.unreal.modelrouter.platform.sync.PlatformDataSyncService> platformDataSyncServiceProvider,
            final ObjectProvider<PlatformAuthCache> platformAuthCacheProvider,
            final ObjectProvider<TeacherAccessService> teacherAccessServiceProvider) {
        return createReactiveAuthenticationManager(
                platformDataSyncServiceProvider.getIfAvailable(),
                platformAuthCacheProvider.getIfAvailable(),
                teacherAccessServiceProvider.getIfAvailable());
    }

    /** Backwards-compatible overload for callers that do not use teacher device authentication. */
    public ReactiveAuthenticationManager reactiveAuthenticationManager(
            final ObjectProvider<org.unreal.modelrouter.platform.sync.PlatformDataSyncService> platformDataSyncServiceProvider,
            final ObjectProvider<PlatformAuthCache> platformAuthCacheProvider) {
        return createReactiveAuthenticationManager(
                platformDataSyncServiceProvider.getIfAvailable(),
                platformAuthCacheProvider.getIfAvailable(),
                null);
    }

    private ReactiveAuthenticationManager createReactiveAuthenticationManager(
            final org.unreal.modelrouter.platform.sync.PlatformDataSyncService platformDataSyncService,
            final PlatformAuthCache platformAuthCache,
            final TeacherAccessService teacherAccessService) {
        log.info("创建响应式认证管理器");
        return new CustomReactiveAuthenticationManager(
                apiKeyService,
                jwtTokenValidator,
                securityProperties,
                platformDataSyncService,
                platformAuthCache,
                teacherAccessService,
                applicationContext.getBean(org.unreal.modelrouter.auth.campus.key.CampusGatewayKeyService.class)
        );
    }

    /**
     * 配置认证管理器
     */
    @Bean
    public AuthenticationManager authenticationManager(final PasswordEncoder passwordEncoder) {
        // 动态获取UserDetailsService以避免循环依赖
        UserDetailsService userDetailsService = applicationContext.getBean(UserDetailsService.class);
        log.info("=== AuthenticationManager created with UserDetailsService: {} ===", 
            userDetailsService.getClass().getName());

        return new org.springframework.security.authentication.ProviderManager(
                List.of(
                        new DaoAuthenticationProvider(passwordEncoder) {{
                            setUserDetailsService(userDetailsService);
                        }}
                )
        );
    }

    /**
     * 配置认证转换器
     */
    @Bean
    public ServerAuthenticationConverter serverAuthenticationConverter(
            final TeacherAccessProperties teacherAccessProperties,
            final ClientCertificateFingerprintExtractor certificateExtractor) {
        return new DefaultAuthenticationConverter(
                securityProperties, teacherAccessProperties, certificateExtractor);
    }

}

