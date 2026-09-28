package org.unreal.modelrouter.auth.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.model.ApiKeyAuthentication;
import org.unreal.modelrouter.auth.security.model.JwtAuthentication;
import org.unreal.modelrouter.auth.teacher.config.TeacherAccessProperties;
import org.unreal.modelrouter.auth.teacher.model.TeacherAccessAuthentication;
import org.unreal.modelrouter.auth.teacher.model.TeacherRequestProof;
import org.unreal.modelrouter.auth.teacher.security.ClientCertificateFingerprintExtractor;
import org.unreal.modelrouter.auth.teacher.security.TeacherEndpointPathFilter;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 默认的认证转换器
 */
public class DefaultAuthenticationConverter implements ServerAuthenticationConverter {

    private final SecurityProperties securityProperties;
    private final TeacherAccessProperties teacherAccessProperties;
    private final ClientCertificateFingerprintExtractor certificateExtractor;

    private static final Logger log = LoggerFactory.getLogger(DefaultAuthenticationConverter.class);

    public DefaultAuthenticationConverter(final SecurityProperties securityProperties) {
        this(securityProperties, new TeacherAccessProperties(), new ClientCertificateFingerprintExtractor());
    }

    public DefaultAuthenticationConverter(
            final SecurityProperties securityProperties,
            final TeacherAccessProperties teacherAccessProperties,
            final ClientCertificateFingerprintExtractor certificateExtractor) {
        this.securityProperties = securityProperties;
        this.teacherAccessProperties = teacherAccessProperties;
        this.certificateExtractor = certificateExtractor;
    }

    @Override
    public Mono<Authentication> convert(final ServerWebExchange exchange) {
        String apiKey = null;
        String jwtToken = null;

        String bearerToken = extractBearerToken(exchange);
        if (teacherAccessProperties.isEnabled()
                && bearerToken != null
                && bearerToken.startsWith(TeacherAccessProperties.TOKEN_PREFIX)) {
            String endpointId = exchange.getAttribute(TeacherEndpointPathFilter.ENDPOINT_ID_ATTRIBUTE);
            TeacherRequestProof proof = new TeacherRequestProof(
                    certificateExtractor.extract(exchange), endpointId);
            return Mono.just(new TeacherAccessAuthentication(bearerToken, proof));
        }

        // 首先尝试提取API Key（如果启用）
        if (securityProperties.getApiKey().isEnabled()) {
            apiKey = extractApiKey(exchange);
        }

        // 然后尝试提取JWT令牌（如果启用）
        if (securityProperties.getJwt().isEnabled()) {
            jwtToken = extractJwtToken(exchange);
        }

        // 如果同时提供了API Key和JWT令牌，则优先使用JWT
        if (jwtToken != null) {
            log.debug("提取到JWT令牌，创建JWT认证对象");
            return Mono.just(new JwtAuthentication(jwtToken));
        } else if (apiKey != null) {
            log.debug("提取到API Key，创建API Key认证对象");
            return Mono.just(new ApiKeyAuthentication(apiKey));
        }

        // 没有找到认证信息
        return Mono.empty();
    }

    /**
     * 提取API Key
     * 优先从配置的 header（默认 X-API-Key）读取，未命中时兜底从 Authorization: Bearer <key> 读取
     */
    private String extractApiKey(final ServerWebExchange exchange) {
        // 1. 优先读取配置的 header（默认 X-API-Key）
        String headerName = securityProperties.getApiKey().getHeaderName();
        List<String> headerValues = exchange.getRequest().getHeaders().get(headerName);
        if (headerValues != null && !headerValues.isEmpty()) {
            return headerValues.get(0);
        }

        // 2. 兼容 OpenAI 标准格式：从 Authorization: Bearer <api-key> 中提取
        String token = extractBearerToken(exchange);
        if (token != null) {
            log.debug("从 Authorization header 提取到 API Key");
            return token;
        }

        return null;
    }

    private String extractBearerToken(final ServerWebExchange exchange) {
        String authHeader = getHeaderIgnoreCase(exchange, "Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 提取JWT令牌
     */
    private String extractJwtToken(final ServerWebExchange exchange) {
        // 首先尝试从配置的自定义头中提取（大小写不敏感）
        String jwtHeader = securityProperties.getJwt().getJwtHeader();
        String authHeader = getHeaderIgnoreCase(exchange, jwtHeader);
        if (authHeader != null) {
            if (authHeader.startsWith("Bearer ")) {
                return authHeader.substring(7);
            }
            return authHeader;
        }
        return null;
    }

    /**
     * 大小写不敏感地获取请求头
     */
    private String getHeaderIgnoreCase(final ServerWebExchange exchange, final String headerName) {
        // 首先尝试直接获取
        List<String> values = exchange.getRequest().getHeaders().get(headerName);
        if (values != null && !values.isEmpty()) {
            return values.get(0);
        }

        // 如果直接获取失败，进行大小写不敏感的搜索
        String lowerHeaderName = headerName.toLowerCase();
        for (String key : exchange.getRequest().getHeaders().keySet()) {
            if (key.toLowerCase().equals(lowerHeaderName)) {
                List<String> headerValues = exchange.getRequest().getHeaders().get(key);
                if (headerValues != null && !headerValues.isEmpty()) {
                    return headerValues.get(0);
                }
            }
        }
        return null;
    }
}
