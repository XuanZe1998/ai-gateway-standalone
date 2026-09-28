// 文件说明：测试 TeacherProvisioningServiceTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.auth.teacher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.auth.campus.config.CampusAuthProperties;
import org.unreal.modelrouter.auth.campus.model.CampusPrincipal;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.auth.teacher.config.TeacherAccessProperties;
import org.unreal.modelrouter.auth.teacher.service.TeacherProvisioningService;
import org.unreal.modelrouter.common.exception.AuthenticationException;
import org.unreal.modelrouter.persistence.jpa.entity.CampusIdentityBindingEntity;
import org.unreal.modelrouter.persistence.jpa.entity.TeacherEndpointEntity;
import org.unreal.modelrouter.persistence.jpa.entity.TeacherTrustedDeviceEntity;
import org.unreal.modelrouter.persistence.jpa.repository.CampusIdentityBindingRepository;
import org.unreal.modelrouter.persistence.jpa.repository.TeacherEndpointRepository;
import org.unreal.modelrouter.persistence.jpa.repository.TeacherTrustedDeviceRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherProvisioningServiceTest {
    private static final String PROVIDER = "https://cas.ntit.edu.cn/cas";

    @Mock
    private CampusIdentityBindingRepository identityRepository;
    @Mock
    private TeacherTrustedDeviceRepository deviceRepository;
    @Mock
    private TeacherEndpointRepository endpointRepository;

    private TeacherAccessProperties properties;
    private TeacherProvisioningService service;

    @BeforeEach
    void setUp() {
        properties = new TeacherAccessProperties();
        properties.setEnabled(true);
        properties.setMaxDevicesPerTeacher(2);
        CampusAuthProperties campusProperties = new CampusAuthProperties();
        campusProperties.setCasBaseUrl(PROVIDER + "/");
        service = new TeacherProvisioningService(
                properties, campusProperties, identityRepository, deviceRepository, endpointRepository);
    }

    @Test
    void resolvesTeacherBindingAndCreatesEndpoint() {
        CampusPrincipal principal = principal(List.of("USER", "TEACHER"));
        CampusIdentityBindingEntity binding = binding();
        when(identityRepository.findByIdentityProviderAndExternalSubjectAndEnabledTrue(
                PROVIDER, "cas-teacher-1")).thenReturn(Optional.of(binding));
        when(endpointRepository.findByIdentityBindingIdAndEnabledTrue("binding-1"))
                .thenReturn(Optional.empty());
        when(endpointRepository.save(any(TeacherEndpointEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TeacherProvisioningService.ProvisionedTeacher result = service.resolveTeacher(principal);

        assertEquals(binding, result.binding());
        assertTrue(result.endpoint().getEndpointId().matches("[A-Za-z0-9_-]{32}"));
        assertTrue(result.endpoint().getCredentialId().startsWith("teacher_"));
    }

    @Test
    void rejectsCampusUserWithoutTeacherRole() {
        AuthenticationException exception = assertThrows(
                AuthenticationException.class,
                () -> service.resolveTeacher(principal(List.of("USER", "STUDENT"))));

        assertEquals("TEACHER_ROLE_REQUIRED", exception.getErrorCode());
    }

    @Test
    void rejectsMissingOrDisabledCampusBinding() {
        CampusPrincipal principal = principal(List.of("USER", "TEACHER"));
        when(identityRepository.findByIdentityProviderAndExternalSubjectAndEnabledTrue(
                PROVIDER, "cas-teacher-1")).thenReturn(Optional.empty());

        AuthenticationException exception = assertThrows(
                AuthenticationException.class, () -> service.resolveTeacher(principal));

        assertEquals("TEACHER_IDENTITY_DISABLED", exception.getErrorCode());
    }

    @Test
    void revokedDeviceCannotBypassActiveDeviceLimit() {
        CampusIdentityBindingEntity binding = binding();
        TeacherTrustedDeviceEntity revoked = TeacherTrustedDeviceEntity.builder()
                .id("record-3")
                .identityBindingId("binding-1")
                .deviceId("device-3")
                .status(TeacherTrustedDeviceEntity.STATUS_REVOKED)
                .certificateFingerprint("old-fingerprint")
                .build();
        when(deviceRepository.findByIdentityBindingIdAndDeviceId("binding-1", "device-3"))
                .thenReturn(Optional.of(revoked));
        when(deviceRepository.countByIdentityBindingIdAndStatus(
                "binding-1", TeacherTrustedDeviceEntity.STATUS_ACTIVE)).thenReturn(2L);

        AuthenticationException exception = assertThrows(AuthenticationException.class,
                () -> service.bindDevice(binding, "device-3", "Office PC", "new-fingerprint"));

        assertEquals("TEACHER_DEVICE_LIMIT_EXCEEDED", exception.getErrorCode());
    }

    @Test
    void certificateIsOptionalWhenMtlsIsDisabled() {
        properties.setRequireClientCertificate(false);
        CampusIdentityBindingEntity binding = binding();
        when(deviceRepository.findByIdentityBindingIdAndDeviceId("binding-1", "device-1"))
                .thenReturn(Optional.empty());
        when(deviceRepository.countByIdentityBindingIdAndStatus(
                "binding-1", TeacherTrustedDeviceEntity.STATUS_ACTIVE)).thenReturn(0L);
        when(deviceRepository.save(any(TeacherTrustedDeviceEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TeacherTrustedDeviceEntity device = service.bindDevice(binding, "device-1", "Browser", null);

        assertNull(device.getCertificateFingerprint());
        assertTrue(TeacherTrustedDeviceEntity.STATUS_ACTIVE.equals(device.getStatus()));
        assertFalse(device.getDeviceId().isBlank());
    }

    private CampusIdentityBindingEntity binding() {
        return CampusIdentityBindingEntity.builder()
                .id("binding-1")
                .identityProvider(PROVIDER)
                .externalSubject("cas-teacher-1")
                .enabled(true)
                .build();
    }

    private CampusPrincipal principal(final List<String> roles) {
        UserIdentity identity = new UserIdentity(
                "T1001", "teacher01", null, null,
                88L, "南通理工学院", "NTIT", true,
                1, 1001L, 2);
        return new CampusPrincipal(
                "cas-teacher-1", "teacher01", "T1001", "张老师",
                "D01", "计算机学院", "teacher-code", "教师",
                1001L, "T1001", 2, 88L, "南通理工学院", "NTIT",
                roles, List.of("CHAT"), List.of("TEACHER", "PLAYGROUND", "PROFILE"), identity);
    }
}
