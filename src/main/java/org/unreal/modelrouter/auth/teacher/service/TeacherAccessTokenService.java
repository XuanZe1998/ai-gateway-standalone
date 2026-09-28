package org.unreal.modelrouter.auth.teacher.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.auth.teacher.config.TeacherAccessProperties;
import org.unreal.modelrouter.persistence.jpa.entity.TeacherEndpointEntity;
import org.unreal.modelrouter.persistence.jpa.entity.CampusIdentityBindingEntity;
import org.unreal.modelrouter.persistence.jpa.entity.TeacherTrustedDeviceEntity;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 签发和验证设备绑定的教师短期令牌。 */
@Service
public class TeacherAccessTokenService {

    private static final String CLAIM_AUDIENCE = "aud";
    private static final String CLAIM_BINDING_ID = "binding_id";
    private static final String CLAIM_SYSTEM_USER_ID = "system_user_id";
    private static final String CLAIM_USER_ACCOUNT = "user_account";
    private static final String CLAIM_STAFF_NO = "staff_no";
    private static final String CLAIM_DEVICE_ID = "device_id";
    private static final String CLAIM_ENDPOINT_ID = "endpoint_id";
    private static final String CLAIM_CREDENTIAL_ID = "credential_id";
    private static final String CLAIM_PERMISSIONS = "permissions";
    private static final String CLAIM_CONFIRMATION = "cnf";
    private static final String CLAIM_CERTIFICATE_THUMBPRINT = "x5t#S256";

    private final TeacherAccessProperties properties;

    public TeacherAccessTokenService(final TeacherAccessProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validateConfiguration() {
        if (properties.isEnabled()) {
            assertEnabledAndConfigured();
        }
    }

    public IssuedToken issue(final CampusIdentityBindingEntity binding,
                             final TeacherTrustedDeviceEntity device,
                             final TeacherEndpointEntity endpoint) {
        assertEnabledAndConfigured();
        if (!binding.getId().equals(device.getIdentityBindingId())
                || !binding.getId().equals(endpoint.getIdentityBindingId())) {
            throw new IllegalArgumentException("教师、设备和专属入口不属于同一身份");
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getAccessTokenTtl());
        List<String> permissions = TeacherAccessService.splitValues(endpoint.getPermissions());

        var builder = Jwts.builder()
                .subject(binding.getExternalSubject())
                .issuer(properties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_AUDIENCE, properties.getAudience())
                .claim(CLAIM_BINDING_ID, binding.getId())
                .claim(CLAIM_SYSTEM_USER_ID, binding.getSystemUserId())
                .claim(CLAIM_USER_ACCOUNT, binding.getUserAccount())
                .claim(CLAIM_STAFF_NO, binding.getStaffNo())
                .claim(CLAIM_DEVICE_ID, device.getDeviceId())
                .claim(CLAIM_ENDPOINT_ID, endpoint.getEndpointId())
                .claim(CLAIM_CREDENTIAL_ID, endpoint.getCredentialId())
                .claim(CLAIM_PERMISSIONS, permissions);
        if (properties.isRequireClientCertificate()) {
            if (device.getCertificateFingerprint() == null || device.getCertificateFingerprint().isBlank()) {
                throw authenticationError("受信设备缺少证书指纹", "DEVICE_CERT_REQUIRED");
            }
            builder.claim(CLAIM_CONFIRMATION,
                    Map.of(CLAIM_CERTIFICATE_THUMBPRINT, device.getCertificateFingerprint()));
        }
        String jwt = builder.signWith(signingKey()).compact();

        return new IssuedToken(TeacherAccessProperties.TOKEN_PREFIX + jwt, expiresAt);
    }

    public TokenClaims validate(final String accessToken) {
        assertEnabledAndConfigured();
        if (accessToken == null || !accessToken.startsWith(TeacherAccessProperties.TOKEN_PREFIX)) {
            throw new org.unreal.modelrouter.common.exception.AuthenticationException(
                    "无效的教师访问令牌", "TEACHER_TOKEN_INVALID");
        }

        try {
            String jwt = accessToken.substring(TeacherAccessProperties.TOKEN_PREFIX.length());
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .requireIssuer(properties.getIssuer())
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();

            if (claims.getAudience() == null
                    || !claims.getAudience().contains(properties.getAudience())) {
                throw authenticationError("教师令牌受众不匹配", "TEACHER_TOKEN_AUDIENCE_MISMATCH");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> confirmation = claims.get(CLAIM_CONFIRMATION, Map.class);
            String fingerprint = confirmation == null
                    ? null : String.valueOf(confirmation.get(CLAIM_CERTIFICATE_THUMBPRINT));

            return new TokenClaims(
                    claims.getSubject(),
                    claims.get(CLAIM_BINDING_ID, String.class),
                    claims.get(CLAIM_DEVICE_ID, String.class),
                    claims.get(CLAIM_ENDPOINT_ID, String.class),
                    claims.get(CLAIM_CREDENTIAL_ID, String.class),
                    fingerprint
            );
        } catch (org.unreal.modelrouter.common.exception.AuthenticationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new org.unreal.modelrouter.common.exception.AuthenticationException(
                    "教师访问令牌已过期或无效", exception, "TEACHER_TOKEN_INVALID");
        }
    }

    private void assertEnabledAndConfigured() {
        if (!properties.isEnabled()) {
            throw authenticationError("教师设备绑定访问未启用", "TEACHER_ACCESS_DISABLED");
        }
        if (properties.getIssuer() == null || properties.getIssuer().isBlank()
                || properties.getAudience() == null || properties.getAudience().isBlank()) {
            throw new IllegalStateException("教师令牌 issuer 和 audience 不能为空");
        }
        if (properties.getTokenSecret() == null
                || properties.getTokenSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("jairouter.teacher-access.token-secret 必须至少 32 字节");
        }
        if (properties.getAccessTokenTtl() == null
                || properties.getAccessTokenTtl().isZero()
                || properties.getAccessTokenTtl().isNegative()) {
            throw new IllegalStateException("教师访问令牌 TTL 必须大于 0");
        }
        if (properties.getMaxDevicesPerTeacher() < 1) {
            throw new IllegalStateException("每位教师的受信设备上限必须大于 0");
        }
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(properties.getTokenSecret().getBytes(StandardCharsets.UTF_8));
    }

    private org.unreal.modelrouter.common.exception.AuthenticationException authenticationError(
            final String message, final String code) {
        return new org.unreal.modelrouter.common.exception.AuthenticationException(message, code);
    }

    public record IssuedToken(String accessToken, Instant expiresAt) {
    }

    public record TokenClaims(
            String subject,
            String bindingId,
            String deviceId,
            String endpointId,
            String credentialId,
            String certificateFingerprint
    ) {
    }
}
