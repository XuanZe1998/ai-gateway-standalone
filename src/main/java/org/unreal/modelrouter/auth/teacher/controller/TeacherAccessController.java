package org.unreal.modelrouter.auth.teacher.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.unreal.modelrouter.auth.campus.model.CampusPrincipal;
import org.unreal.modelrouter.auth.campus.model.CampusAuthentication;
import org.unreal.modelrouter.auth.teacher.config.TeacherAccessProperties;
import org.unreal.modelrouter.auth.teacher.security.ClientCertificateFingerprintExtractor;
import org.unreal.modelrouter.auth.teacher.service.TeacherAccessTokenService;
import org.unreal.modelrouter.auth.teacher.service.TeacherProvisioningService;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.persistence.jpa.entity.TeacherTrustedDeviceEntity;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** CAS 教师的受管设备和模型访问配置接口。 */
@RestController
@RequestMapping("/api/teacher")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "jairouter.teacher-access.enabled", havingValue = "true")
public class TeacherAccessController {
    private final TeacherAccessProperties properties;
    private final TeacherProvisioningService provisioningService;
    private final TeacherAccessTokenService tokenService;
    private final ClientCertificateFingerprintExtractor certificateExtractor;

    @PostMapping("/devices/bind")
    public Mono<ResponseEntity<RouterResponse<AccessProfile>>> bindDevice(
            final Authentication authentication,
            @Valid @RequestBody final BindDeviceRequest request,
            final ServerWebExchange exchange) {
        String fingerprint = certificateExtractor.extract(exchange);
        return Mono.fromCallable(() -> {
            TeacherProvisioningService.ProvisionedTeacher teacher = requireTeacher(authentication);
            TeacherTrustedDeviceEntity device = provisioningService.bindDevice(
                    teacher.binding(), request.deviceId(), request.deviceName(), fingerprint);
            return ResponseEntity.ok(RouterResponse.success(issueProfile(teacher, device), "受管设备绑定成功"));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/access-profile")
    public Mono<ResponseEntity<RouterResponse<AccessProfile>>> getAccessProfile(
            final Authentication authentication,
            @RequestParam(required = false) final String deviceId,
            final ServerWebExchange exchange) {
        String fingerprint = certificateExtractor.extract(exchange);
        return Mono.fromCallable(() -> {
            TeacherProvisioningService.ProvisionedTeacher teacher = requireTeacher(authentication);
            TeacherTrustedDeviceEntity device = provisioningService.listDevices(teacher.binding()).stream()
                    .filter(value -> TeacherTrustedDeviceEntity.STATUS_ACTIVE.equals(value.getStatus()))
                    .filter(value -> properties.isRequireClientCertificate()
                            ? fingerprint != null && fingerprint.equals(value.getCertificateFingerprint())
                            : deviceId != null && deviceId.equals(value.getDeviceId()))
                    .findFirst()
                    .orElseThrow(() -> new org.unreal.modelrouter.common.exception.AuthenticationException(
                            "当前设备尚未绑定", "TEACHER_DEVICE_NOT_BOUND"));
            return ResponseEntity.ok(RouterResponse.success(issueProfile(teacher, device)));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/devices")
    public Mono<ResponseEntity<RouterResponse<List<DeviceView>>>> listDevices(
            final Authentication authentication) {
        return Mono.fromCallable(() -> {
            TeacherProvisioningService.ProvisionedTeacher teacher = requireTeacher(authentication);
            List<DeviceView> devices = provisioningService.listDevices(teacher.binding()).stream()
                    .map(DeviceView::from).toList();
            return ResponseEntity.ok(RouterResponse.success(devices));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/devices/{deviceId}")
    public Mono<ResponseEntity<RouterResponse<Void>>> revokeDevice(
            final Authentication authentication,
            @PathVariable final String deviceId) {
        return Mono.fromCallable(() -> {
            TeacherProvisioningService.ProvisionedTeacher teacher = requireTeacher(authentication);
            provisioningService.revokeDevice(teacher.binding(), deviceId);
            return ResponseEntity.ok(RouterResponse.<Void>success(null, "设备已撤销"));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private TeacherProvisioningService.ProvisionedTeacher requireTeacher(final Authentication authentication) {
        // CampusPrincipal implements Principal, which WebFlux's built-in resolver
        // otherwise resolves to the Authentication object before @AuthenticationPrincipal.
        if (!(authentication instanceof CampusAuthentication campus) || !authentication.isAuthenticated()) {
            throw new org.unreal.modelrouter.common.exception.AuthenticationException(
                    "请先通过学校统一认证登录", "TEACHER_CAS_LOGIN_REQUIRED");
        }
        CampusPrincipal principal = campus.getPrincipal();
        return provisioningService.resolveTeacher(principal);
    }

    private AccessProfile issueProfile(
            final TeacherProvisioningService.ProvisionedTeacher teacher,
            final TeacherTrustedDeviceEntity device) {
        TeacherAccessTokenService.IssuedToken issued = tokenService.issue(
                teacher.binding(), device, teacher.endpoint());
        String root = properties.getPublicBaseUrl().replaceAll("/+$", "");
        return new AccessProfile(root + "/u/" + teacher.endpoint().getEndpointId() + "/v1",
                issued.accessToken(), issued.expiresAt(), teacher.endpoint().getCredentialId(),
                device.getDeviceId());
    }

    public record BindDeviceRequest(@NotBlank String deviceId, String deviceName) { }
    public record AccessProfile(String baseUrl, String accessToken, Instant expiresAt,
                                String credentialId, String deviceId) { }
    public record DeviceView(String deviceId, String deviceName, String status,
                             LocalDateTime certificateExpiresAt, LocalDateTime lastSeenAt,
                             LocalDateTime createdAt) {
        private static DeviceView from(final TeacherTrustedDeviceEntity entity) {
            return new DeviceView(entity.getDeviceId(), entity.getDeviceName(), entity.getStatus(),
                    entity.getCertificateExpiresAt(), entity.getLastSeenAt(), entity.getCreatedAt());
        }
    }
}
