package org.unreal.modelrouter.auth.teacher.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.auth.teacher.config.TeacherAccessProperties;
import org.unreal.modelrouter.auth.teacher.model.TeacherRequestProof;
import org.unreal.modelrouter.persistence.jpa.entity.TeacherEndpointEntity;
import org.unreal.modelrouter.persistence.jpa.entity.CampusIdentityBindingEntity;
import org.unreal.modelrouter.persistence.jpa.entity.TeacherTrustedDeviceEntity;
import org.unreal.modelrouter.persistence.jpa.repository.TeacherEndpointRepository;
import org.unreal.modelrouter.persistence.jpa.repository.CampusIdentityBindingRepository;
import org.unreal.modelrouter.persistence.jpa.repository.TeacherTrustedDeviceRepository;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/** 校验教师身份、受信设备和专属 URL 的强绑定关系。 */
@Service
@RequiredArgsConstructor
public class TeacherAccessService {

    private final TeacherAccessProperties properties;
    private final TeacherAccessTokenService tokenService;
    private final CampusIdentityBindingRepository identityRepository;
    private final TeacherTrustedDeviceRepository deviceRepository;
    private final TeacherEndpointRepository endpointRepository;

    @Transactional(readOnly = true)
    public AuthenticationResult authenticate(final String token, final TeacherRequestProof proof) {
        TeacherAccessTokenService.TokenClaims claims = tokenService.validate(token);

        if (proof == null || isBlank(proof.endpointId())) {
            throw authError("教师专属 URL 缺失", "TEACHER_ENDPOINT_REQUIRED");
        }
        if (!claims.endpointId().equals(proof.endpointId())) {
            throw authError("专属 URL 不属于当前教师", "ENDPOINT_OWNER_MISMATCH");
        }
        if (properties.isRequireClientCertificate() && isBlank(proof.certificateFingerprint())) {
            throw authError("未提供受信设备证书", "DEVICE_CERT_REQUIRED");
        }
        if (properties.isRequireClientCertificate()
                && !safeEquals(claims.certificateFingerprint(), proof.certificateFingerprint())) {
            throw authError("令牌与当前设备证书不匹配", "TOKEN_DEVICE_MISMATCH");
        }

        CampusIdentityBindingEntity binding = identityRepository.findById(claims.bindingId())
                .filter(CampusIdentityBindingEntity::isEnabled)
                .orElseThrow(() -> authError("教师身份已停用", "TEACHER_IDENTITY_DISABLED"));
        if (!safeEquals(binding.getExternalSubject(), claims.subject())) {
            throw authError("令牌教师身份不匹配", "TEACHER_IDENTITY_MISMATCH");
        }

        TeacherEndpointEntity endpoint = endpointRepository.findByEndpointIdAndEnabledTrue(claims.endpointId())
                .filter(value -> value.getIdentityBindingId().equals(binding.getId()))
                .orElseThrow(() -> authError("教师专属入口已停用", "TEACHER_ENDPOINT_DISABLED"));
        if (!safeEquals(endpoint.getCredentialId(), claims.credentialId())) {
            throw authError("教师逻辑凭据不匹配", "TEACHER_CREDENTIAL_MISMATCH");
        }

        TeacherTrustedDeviceEntity device = deviceRepository
                .findByIdentityBindingIdAndDeviceIdAndStatus(
                        binding.getId(), claims.deviceId(), TeacherTrustedDeviceEntity.STATUS_ACTIVE)
                .orElseThrow(() -> authError("受信设备已撤销或不存在", "TEACHER_DEVICE_REVOKED"));
        if (properties.isRequireClientCertificate()
                && !safeEquals(device.getCertificateFingerprint(), proof.certificateFingerprint())) {
            throw authError("设备证书已变更或失效", "TEACHER_DEVICE_CERT_MISMATCH");
        }
        if (properties.isRequireClientCertificate() && device.getCertificateExpiresAt() != null
                && LocalDateTime.now().isAfter(device.getCertificateExpiresAt())) {
            throw authError("设备证书已过期", "TEACHER_DEVICE_CERT_EXPIRED");
        }

        UserIdentity identity = new UserIdentity(
                String.valueOf(binding.getSystemUserId()),
                binding.getUserAccount(),
                endpoint.getCredentialId(),
                "teacher-device-bound",
                binding.getEnterpriseId(),
                binding.getEnterpriseName(),
                binding.getCompanyId(),
                true,
                binding.getUserType(),
                binding.getSystemUserId(),
                binding.getVerifyStatus()
        );
        return new AuthenticationResult(identity, splitValues(endpoint.getPermissions()));
    }

    public static List<String> splitValues(final String values) {
        if (values == null || values.isBlank()) {
            return List.of();
        }
        return Arrays.stream(values.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    private boolean safeEquals(final String left, final String right) {
        return left != null && left.equals(right);
    }

    private boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }

    private org.unreal.modelrouter.common.exception.AuthenticationException authError(
            final String message, final String code) {
        return new org.unreal.modelrouter.common.exception.AuthenticationException(message, code);
    }

    public record AuthenticationResult(UserIdentity identity, List<String> permissions) {
    }
}

