// 文件说明：测试 TeacherAccessServiceTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.auth.teacher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.auth.teacher.config.TeacherAccessProperties;
import org.unreal.modelrouter.auth.teacher.model.TeacherRequestProof;
import org.unreal.modelrouter.auth.teacher.service.TeacherAccessService;
import org.unreal.modelrouter.auth.teacher.service.TeacherAccessTokenService;
import org.unreal.modelrouter.common.exception.AuthenticationException;
import org.unreal.modelrouter.persistence.jpa.entity.TeacherEndpointEntity;
import org.unreal.modelrouter.persistence.jpa.entity.CampusIdentityBindingEntity;
import org.unreal.modelrouter.persistence.jpa.entity.TeacherTrustedDeviceEntity;
import org.unreal.modelrouter.persistence.jpa.repository.TeacherEndpointRepository;
import org.unreal.modelrouter.persistence.jpa.repository.CampusIdentityBindingRepository;
import org.unreal.modelrouter.persistence.jpa.repository.TeacherTrustedDeviceRepository;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherAccessServiceTest {

    private static final String SECRET = "teacher-access-test-secret-32-bytes-minimum";
    private static final String FINGERPRINT = "device-certificate-fingerprint";

    @Mock
    private CampusIdentityBindingRepository identityRepository;
    @Mock
    private TeacherTrustedDeviceRepository deviceRepository;
    @Mock
    private TeacherEndpointRepository endpointRepository;

    private TeacherAccessTokenService tokenService;
    private TeacherAccessService accessService;
    private CampusIdentityBindingEntity binding;
    private TeacherTrustedDeviceEntity device;
    private TeacherEndpointEntity endpoint;
    private String token;

    @BeforeEach
    void setUp() {
        TeacherAccessProperties properties = new TeacherAccessProperties();
        properties.setEnabled(true);
        properties.setTokenSecret(SECRET);
        properties.setAccessTokenTtl(Duration.ofMinutes(5));
        tokenService = new TeacherAccessTokenService(properties);
        accessService = new TeacherAccessService(
                properties, tokenService, identityRepository, deviceRepository, endpointRepository);

        binding = CampusIdentityBindingEntity.builder()
                .id("binding-1")
                .identityProvider("https://idp.ntit.edu.cn")
                .externalSubject("teacher-subject")
                .systemUserId(1001L)
                .userAccount("teacher01")
                .roles("TEACHER")
                .userType(0)
                .verifyStatus(2)
                .enabled(true)
                .build();
        device = TeacherTrustedDeviceEntity.builder()
                .id("device-record-1")
                .identityBindingId(binding.getId())
                .deviceId("managed-device-1")
                .certificateFingerprint(FINGERPRINT)
                .status(TeacherTrustedDeviceEntity.STATUS_ACTIVE)
                .build();
        endpoint = TeacherEndpointEntity.builder()
                .endpointId("teacherEndpointId1234567890")
                .identityBindingId(binding.getId())
                .credentialId("teacher_credential_1")
                .permissions("USER,READ,WRITE,CHAT")
                .enabled(true)
                .build();
        token = tokenService.issue(binding, device, endpoint).accessToken();
    }

    @Test
    void acceptsMatchingTeacherEndpointAndDeviceCertificate() {
        stubActiveRecords();

        TeacherAccessService.AuthenticationResult result = accessService.authenticate(
                token, new TeacherRequestProof(FINGERPRINT, endpoint.getEndpointId()));

        assertEquals("teacher01", result.identity().userAccount());
        assertEquals("teacher_credential_1", result.identity().apiKeyId());
        assertEquals(1001L, result.identity().systemUserId());
        assertTrue(result.permissions().contains("CHAT"));
    }

    @Test
    void rejectsCopiedTokenAndUrlWithoutDeviceCertificate() {
        AuthenticationException exception = assertThrows(AuthenticationException.class,
                () -> accessService.authenticate(
                        token, new TeacherRequestProof(null, endpoint.getEndpointId())));

        assertEquals("DEVICE_CERT_REQUIRED", exception.getErrorCode());
    }

    @Test
    void rejectsCopiedTokenAndUrlFromAnotherDevice() {
        AuthenticationException exception = assertThrows(AuthenticationException.class,
                () -> accessService.authenticate(
                        token, new TeacherRequestProof("family-device-certificate", endpoint.getEndpointId())));

        assertEquals("TOKEN_DEVICE_MISMATCH", exception.getErrorCode());
    }

    @Test
    void rejectsAnotherTeachersEndpoint() {
        AuthenticationException exception = assertThrows(AuthenticationException.class,
                () -> accessService.authenticate(
                        token, new TeacherRequestProof(FINGERPRINT, "anotherTeacherEndpoint123")));

        assertEquals("ENDPOINT_OWNER_MISMATCH", exception.getErrorCode());
    }

    @Test
    void rejectsRevokedDeviceImmediately() {
        when(identityRepository.findById(binding.getId())).thenReturn(Optional.of(binding));
        when(endpointRepository.findByEndpointIdAndEnabledTrue(endpoint.getEndpointId()))
                .thenReturn(Optional.of(endpoint));
        when(deviceRepository.findByIdentityBindingIdAndDeviceIdAndStatus(
                binding.getId(), device.getDeviceId(), TeacherTrustedDeviceEntity.STATUS_ACTIVE))
                .thenReturn(Optional.empty());

        AuthenticationException exception = assertThrows(AuthenticationException.class,
                () -> accessService.authenticate(
                        token, new TeacherRequestProof(FINGERPRINT, endpoint.getEndpointId())));

        assertEquals("TEACHER_DEVICE_REVOKED", exception.getErrorCode());
    }

    private void stubActiveRecords() {
        when(identityRepository.findById(binding.getId())).thenReturn(Optional.of(binding));
        when(endpointRepository.findByEndpointIdAndEnabledTrue(endpoint.getEndpointId()))
                .thenReturn(Optional.of(endpoint));
        when(deviceRepository.findByIdentityBindingIdAndDeviceIdAndStatus(
                binding.getId(), device.getDeviceId(), TeacherTrustedDeviceEntity.STATUS_ACTIVE))
                .thenReturn(Optional.of(device));
    }
}
