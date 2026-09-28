// 文件说明：测试 TeacherSessionControllerTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.auth.teacher;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.unreal.modelrouter.auth.campus.model.CampusAuthentication;
import org.unreal.modelrouter.auth.campus.model.CampusPrincipal;
import org.unreal.modelrouter.auth.teacher.config.TeacherAccessProperties;
import org.unreal.modelrouter.auth.teacher.controller.TeacherAccessController;
import org.unreal.modelrouter.auth.teacher.security.ClientCertificateFingerprintExtractor;
import org.unreal.modelrouter.auth.teacher.service.TeacherAccessTokenService;
import org.unreal.modelrouter.auth.teacher.service.TeacherProvisioningService;
import org.unreal.modelrouter.persistence.jpa.entity.CampusIdentityBindingEntity;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.Mockito.*;

class TeacherSessionControllerTest {
    @Test
    void webFluxResolvesCampusSessionForDeviceListWithoutArgumentTypeMismatch() {
        var principal = mock(CampusPrincipal.class);
        when(principal.roles()).thenReturn(List.of("TEACHER", "USER"));
        when(principal.permissions()).thenReturn(List.of());
        var auth = new CampusAuthentication(principal);
        var service = mock(TeacherProvisioningService.class);
        var binding = new CampusIdentityBindingEntity();
        when(service.resolveTeacher(principal))
                .thenReturn(new TeacherProvisioningService.ProvisionedTeacher(binding, null));
        when(service.listDevices(binding)).thenReturn(List.of());
        var controller = new TeacherAccessController(new TeacherAccessProperties(), service,
                mock(TeacherAccessTokenService.class), new ClientCertificateFingerprintExtractor());

        WebTestClient.bindToController(controller)
                .webFilter((exchange, chain) -> chain.filter(exchange.mutate().principal(Mono.just(auth)).build()))
                .build().get().uri("/api/teacher/devices").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.data").isArray();
        verify(service).resolveTeacher(principal);
    }
}
