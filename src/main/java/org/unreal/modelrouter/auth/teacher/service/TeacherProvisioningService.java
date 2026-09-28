package org.unreal.modelrouter.auth.teacher.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.unreal.modelrouter.auth.campus.config.CampusAuthProperties;
import org.unreal.modelrouter.auth.campus.model.CampusPrincipal;
import org.unreal.modelrouter.auth.teacher.config.TeacherAccessProperties;
import org.unreal.modelrouter.persistence.jpa.entity.CampusIdentityBindingEntity;
import org.unreal.modelrouter.persistence.jpa.entity.TeacherEndpointEntity;
import org.unreal.modelrouter.persistence.jpa.entity.TeacherTrustedDeviceEntity;
import org.unreal.modelrouter.persistence.jpa.repository.CampusIdentityBindingRepository;
import org.unreal.modelrouter.persistence.jpa.repository.TeacherEndpointRepository;
import org.unreal.modelrouter.persistence.jpa.repository.TeacherTrustedDeviceRepository;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** 为已通过 CAS 映射的教师创建受管设备和专属入口。 */
@Service
@RequiredArgsConstructor
public class TeacherProvisioningService {
    private final TeacherAccessProperties properties;
    private final CampusAuthProperties campusAuthProperties;
    private final CampusIdentityBindingRepository identityRepository;
    private final TeacherTrustedDeviceRepository deviceRepository;
    private final TeacherEndpointRepository endpointRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public ProvisionedTeacher resolveTeacher(final CampusPrincipal principal) {
        assertEnabled();
        if (principal == null || !principal.roles().contains("TEACHER")) {
            throw authError("当前校园用户不是教师", "TEACHER_ROLE_REQUIRED");
        }
        CampusIdentityBindingEntity binding = identityRepository
                .findByIdentityProviderAndExternalSubjectAndEnabledTrue(
                        campusAuthProperties.identityProviderUrl(), principal.subject())
                .orElseThrow(() -> authError("教师校园身份未绑定或已停用", "TEACHER_IDENTITY_DISABLED"));
        TeacherEndpointEntity endpoint = endpointRepository
                .findByIdentityBindingIdAndEnabledTrue(binding.getId())
                .orElseGet(() -> endpointRepository.save(TeacherEndpointEntity.builder()
                        .endpointId(randomUrlId())
                        .identityBindingId(binding.getId())
                        .credentialId("teacher_" + UUID.randomUUID().toString().replace("-", ""))
                        .permissions(String.join(",", properties.getDefaultPermissions()))
                        .enabled(true)
                        .build()));
        return new ProvisionedTeacher(binding, endpoint);
    }

    @Transactional
    public TeacherTrustedDeviceEntity bindDevice(
            final CampusIdentityBindingEntity binding,
            final String deviceId,
            final String deviceName,
            final String certificateFingerprint) {
        if (deviceId == null || deviceId.isBlank()) {
            throw authError("设备标识不能为空", "TEACHER_DEVICE_ID_REQUIRED");
        }
        if (properties.isRequireClientCertificate()
                && (certificateFingerprint == null || certificateFingerprint.isBlank())) {
            throw authError("未提供受信设备证书", "DEVICE_CERT_REQUIRED");
        }
        TeacherTrustedDeviceEntity existing = deviceRepository
                .findByIdentityBindingIdAndDeviceId(binding.getId(), deviceId).orElse(null);
        boolean consumesSlot = existing == null
                || !TeacherTrustedDeviceEntity.STATUS_ACTIVE.equals(existing.getStatus());
        if (consumesSlot && deviceRepository.countByIdentityBindingIdAndStatus(
                binding.getId(), TeacherTrustedDeviceEntity.STATUS_ACTIVE)
                >= properties.getMaxDevicesPerTeacher()) {
            throw authError("已达到教师受信设备数量上限", "TEACHER_DEVICE_LIMIT_EXCEEDED");
        }
        TeacherTrustedDeviceEntity device = existing == null
                ? TeacherTrustedDeviceEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .identityBindingId(binding.getId())
                    .deviceId(deviceId)
                    .build()
                : existing;
        device.setDeviceName(deviceName);
        device.setCertificateFingerprint(properties.isRequireClientCertificate()
                ? certificateFingerprint : null);
        device.setCertificateExpiresAt(null);
        device.setStatus(TeacherTrustedDeviceEntity.STATUS_ACTIVE);
        device.setRevokedAt(null);
        device.setLastSeenAt(LocalDateTime.now());
        return deviceRepository.save(device);
    }

    @Transactional
    public void revokeDevice(final CampusIdentityBindingEntity binding, final String deviceId) {
        TeacherTrustedDeviceEntity device = deviceRepository
                .findByIdentityBindingIdAndDeviceId(binding.getId(), deviceId)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在"));
        device.setStatus(TeacherTrustedDeviceEntity.STATUS_REVOKED);
        device.setRevokedAt(LocalDateTime.now());
        deviceRepository.save(device);
    }

    @Transactional(readOnly = true)
    public List<TeacherTrustedDeviceEntity> listDevices(final CampusIdentityBindingEntity binding) {
        return deviceRepository.findByIdentityBindingIdOrderByCreatedAtDesc(binding.getId());
    }

    private String randomUrlId() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void assertEnabled() {
        if (!properties.isEnabled()) {
            throw authError("教师设备绑定访问未启用", "TEACHER_ACCESS_DISABLED");
        }
    }

    private org.unreal.modelrouter.common.exception.AuthenticationException authError(
            final String message, final String code) {
        return new org.unreal.modelrouter.common.exception.AuthenticationException(message, code);
    }

    public record ProvisionedTeacher(
            CampusIdentityBindingEntity binding,
            TeacherEndpointEntity endpoint
    ) { }
}



