// 文件说明：测试 CasAuthenticationControllerTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.auth.campus.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.unreal.modelrouter.auth.campus.config.CampusAuthProperties;
import org.unreal.modelrouter.auth.campus.service.CasSingleLogoutService;
import org.unreal.modelrouter.common.exception.AuthenticationException;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CasAuthenticationControllerTest {
    private CasAuthenticationController controller;

    @BeforeEach
    void setUp() {
        CampusAuthProperties properties = new CampusAuthProperties();
        properties.setAllowedTargetPrefixes(List.of("/admin"));
        controller = new CasAuthenticationController(properties, null, null, null);
    }

    @Test
    void acceptsConfiguredRelativeTarget() {
        assertEquals("/admin/auth/callback?from=login",
                controller.validateTarget("/admin/auth/callback?from=login"));
    }

    @Test
    void rejectsExternalAndProtocolRelativeTargets() {
        assertTargetRejected("https://evil.example/steal");
        assertTargetRejected("//evil.example/steal");
        assertTargetRejected("%2F%2Fevil.example/steal");
        assertTargetRejected("%252F%252Fevil.example/steal");
    }

    @Test
    void rejectsTraversalAndBackslashBypasses() {
        assertTargetRejected("/admin/../outside");
        assertTargetRejected("/admin/%2e%2e/outside");
        assertTargetRejected("/admin\\..\\outside");
    }

    @Test
    void acceptsBackChannelLogoutOnDedicatedAndOriginalCallbackPaths() {
        CasSingleLogoutService logoutService = mock(CasSingleLogoutService.class);
        when(logoutService.invalidate(anyString())).thenReturn(Mono.empty());
        CasAuthenticationController withLogout = new CasAuthenticationController(
                new CampusAuthProperties(), null, null, logoutService);
        WebTestClient client = WebTestClient.bindToController(withLogout).build();
        for (String path : List.of("/api/auth/cas/slo", "/api/auth/cas/callback?state=dynamic")) {
            client.post().uri(path)
                    .body(BodyInserters.fromFormData("logoutRequest", "<LogoutRequest/>"))
                    .exchange().expectStatus().isOk();
        }
        client.post().uri("/api/auth/cas/slo")
                .body(BodyInserters.fromFormData("unrelated", "value"))
                .exchange().expectStatus().isBadRequest();
    }

    private void assertTargetRejected(final String target) {
        assertThrows(AuthenticationException.class, () -> controller.validateTarget(target));
    }
}
