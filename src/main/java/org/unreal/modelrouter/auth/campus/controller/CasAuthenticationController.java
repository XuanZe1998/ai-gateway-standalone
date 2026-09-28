package org.unreal.modelrouter.auth.campus.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import org.springframework.web.util.UriComponentsBuilder;
import org.unreal.modelrouter.auth.campus.config.CampusAuthProperties;
import org.unreal.modelrouter.auth.campus.model.CampusAuthentication;
import org.unreal.modelrouter.auth.campus.model.CampusPrincipal;
import org.unreal.modelrouter.auth.campus.service.CampusIdentityService;
import org.unreal.modelrouter.auth.campus.service.CasProtocolClient;
import org.unreal.modelrouter.auth.campus.service.CasSingleLogoutService;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.common.exception.AuthenticationException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** 学校 CAS 登录、回调、会话查询和注销端点。 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "jairouter.campus-auth.enabled", havingValue = "true")
public class CasAuthenticationController {
    private static final String STATE_KEY = "campus.cas.state";
    private static final String SERVICE_KEY = "campus.cas.service";
    private static final String TARGET_KEY = "campus.cas.target";
    private static final String EXPIRES_KEY = "campus.cas.expires";

    private final CampusAuthProperties properties;
    private final CasProtocolClient casClient;
    private final CampusIdentityService identityService;
    private final CasSingleLogoutService singleLogoutService;
    private final WebSessionServerSecurityContextRepository contextRepository =
            new WebSessionServerSecurityContextRepository();

    @GetMapping("/cas/login")
    public Mono<ResponseEntity<Void>> login(
            @RequestParam(required = false) final String target,
            final ServerWebExchange exchange) {
        String safeTarget = validateTarget(target == null ? "/admin/auth/callback" : target);
        String state = randomState();
        String service = UriComponentsBuilder.fromUriString(properties.callbackServiceUrl())
                .queryParam("state", state).build().encode().toUriString();
        return exchange.getSession().map(session -> {
            session.setMaxIdleTime(properties.getSessionTimeout());
            session.getAttributes().put(STATE_KEY, state);
            session.getAttributes().put(SERVICE_KEY, service);
            session.getAttributes().put(TARGET_KEY, safeTarget);
            session.getAttributes().put(EXPIRES_KEY, Instant.now().plus(properties.getStateTtl()).toEpochMilli());
            String location = UriComponentsBuilder
                    .fromUriString(properties.getCasBaseUrl().replaceAll("/+$", "") + "/login")
                    .queryParam("service", service).build().encode().toUriString();
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
        });
    }

    @GetMapping("/cas/callback")
    public Mono<ResponseEntity<Void>> callback(
            @RequestParam(required = false) final String ticket,
            @RequestParam(required = false) final String state,
            final ServerWebExchange exchange) {
        return exchange.getSession().flatMap(session -> {
            String expectedState = take(session, STATE_KEY, String.class);
            String service = take(session, SERVICE_KEY, String.class);
            String target = take(session, TARGET_KEY, String.class);
            Long expires = take(session, EXPIRES_KEY, Long.class);
            if (expectedState == null || service == null || target == null || expires == null) {
                return Mono.error(authError("CAS 登录会话不存在或已使用", "CAS_STATE_REUSED"));
            }
            if (!constantTimeEquals(expectedState, state)) {
                return Mono.error(authError("CAS state 缺失或已被篡改", "CAS_STATE_INVALID"));
            }
            if (Instant.now().toEpochMilli() > expires) {
                return Mono.error(authError("CAS 登录请求已过期", "CAS_STATE_EXPIRED"));
            }
            return casClient.validate(ticket, service)
                    .publishOn(Schedulers.boundedElastic())
                    .map(identityService::resolve)
                    .flatMap(principal -> {
                        CampusAuthentication authentication = new CampusAuthentication(principal);
                        return session.changeSessionId()
                                .then(contextRepository.save(exchange, new SecurityContextImpl(authentication)))
                                .then(singleLogoutService.register(ticket, session))
                                .thenReturn(ResponseEntity.status(HttpStatus.FOUND)
                                        .location(URI.create(validateTarget(target))).build());
                    });
        });
    }

    /** CAS server-to-server Single Logout; some servers POST to the original service URL. */
    @PostMapping(path = {"/cas/slo", "/cas/callback"},
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Mono<ResponseEntity<Void>> singleLogout(final ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> {
            String request = form.getFirst("logoutRequest");
            if (request == null) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 CAS 注销报文"));
            }
            return Mono.defer(() -> singleLogoutService.invalidate(request));
        }).onErrorMap(IllegalArgumentException.class,
                error -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "CAS 注销报文格式无效", error))
                .thenReturn(ResponseEntity.ok().build());
    }

    @GetMapping("/session")
    public Mono<RouterResponse<SessionView>> session(
            final Authentication authentication,
            final ServerWebExchange exchange) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Mono.error(authError("登录会话不存在或已过期", "SESSION_REQUIRED"));
        }
        Mono<CsrfToken> csrf = exchange.getAttribute(CsrfToken.class.getName());
        Mono<CsrfView> csrfView = csrf == null
                ? Mono.just(new CsrfView("X-XSRF-TOKEN", null))
                : csrf.map(value -> new CsrfView(value.getHeaderName(), value.getToken()));
        return csrfView.map(value -> {
            if (authentication instanceof CampusAuthentication campus) {
                CampusPrincipal principal = campus.getPrincipal();
                return RouterResponse.success(new SessionView(
                        principal.getName(), principal.displayName(), principal.departmentName(),
                        principal.typeCode(), principal.typeName(), principal.roles(),
                        principal.permissions(), principal.portals(), value));
            }
            List<String> roles = authentication.getAuthorities().stream()
                    .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
                    .toList();
            return RouterResponse.success(new SessionView(
                    authentication.getName(), authentication.getName(), null, null, "应急管理员",
                    roles, List.of(), List.of("ADMIN", "PLAYGROUND", "PROFILE"), value));
        });
    }

    @PostMapping("/cas/logout")
    public Mono<RouterResponse<LogoutView>> logout(final ServerWebExchange exchange) {
        String loginReturn = properties.getPublicBaseUrl().replaceAll("/+$", "") + "/admin/login";
        String logoutUrl = UriComponentsBuilder
                .fromUriString(properties.getCasBaseUrl().replaceAll("/+$", "") + "/logout")
                .queryParam("service", loginReturn).build().encode().toUriString();
        return exchange.getSession()
                .flatMap(WebSession::invalidate)
                .then(Mono.fromSupplier(() -> {
                    ResponseCookie expired = ResponseCookie.from("XSRF-TOKEN", "")
                            .path("/").maxAge(0).secure(properties.isCookieSecure()).sameSite("Lax").build();
                    exchange.getResponse().getHeaders().add(HttpHeaders.SET_COOKIE, expired.toString());
                    return RouterResponse.success(new LogoutView(logoutUrl), "本地会话已注销");
                }));
    }

    String validateTarget(final String target) {
        if (target == null || target.isBlank() || target.contains("\r") || target.contains("\n")) {
            throw authError("登录目标地址不合法", "CAS_TARGET_INVALID");
        }
        String decoded = target;
        try {
            // Decode twice so values such as %252f%252fevil.example cannot bypass the allowlist.
            for (int i = 0; i < 2 && decoded.contains("%"); i++) {
                decoded = java.net.URLDecoder.decode(decoded, StandardCharsets.UTF_8);
            }
            URI uri = URI.create(decoded);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getPath() == null
                    || !uri.getPath().startsWith("/") || uri.getPath().startsWith("//")
                    || uri.getPath().contains("\\")) {
                throw new IllegalArgumentException();
            }
            String normalizedPath = URI.create(uri.getPath()).normalize().getPath();
            boolean allowed = properties.getAllowedTargetPrefixes().stream()
                    .anyMatch(prefix -> normalizedPath.equals(prefix)
                            || normalizedPath.startsWith(prefix + "/"));
            if (!allowed) {
                throw authError("登录目标地址不在允许范围", "CAS_TARGET_FORBIDDEN");
            }
            String query = uri.getRawQuery();
            return normalizedPath + (query == null ? "" : "?" + query);
        } catch (AuthenticationException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw authError("登录目标地址不合法", "CAS_TARGET_INVALID");
        }
    }

    private String randomState() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean constantTimeEquals(final String expected, final String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private <T> T take(final WebSession session, final String key, final Class<T> type) {
        Object value = session.getAttributes().remove(key);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    private AuthenticationException authError(final String message, final String code) {
        return new AuthenticationException(message, code);
    }

    public record CsrfView(String headerName, String token) { }
    public record LogoutView(String logoutUrl) { }
    public record SessionView(
            String account, String displayName, String departmentName,
            String typeCode, String typeName, List<String> roles,
            List<String> permissions, List<String> portals, CsrfView csrf) { }
}



