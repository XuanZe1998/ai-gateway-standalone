// 文件说明：测试 CasSingleLogoutServiceTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.auth.campus.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.WebSession;
import org.springframework.web.server.session.DefaultWebSessionManager;
import org.unreal.modelrouter.auth.campus.config.CampusAuthProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CasSingleLogoutServiceTest {
    private static final String LOGOUT_REQUEST = """
            <samlp:LogoutRequest xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol">
              <samlp:SessionIndex>ST-12345</samlp:SessionIndex>
            </samlp:LogoutRequest>
            """;

    @Test
    void invalidatesOnlyTheSessionAssociatedWithTheServiceTicket() {
        DefaultWebSessionManager manager = new DefaultWebSessionManager();
        CampusAuthProperties properties = new CampusAuthProperties();
        CasSingleLogoutService service = new CasSingleLogoutService(
                manager, null, new MockEnvironment(), properties);
        WebSession matching = manager.getSessionStore().createWebSession().block();
        WebSession other = manager.getSessionStore().createWebSession().block();
        assertNotNull(matching);
        assertNotNull(other);
        matching.start();
        other.start();
        matching.save().block();
        other.save().block();

        service.register("ST-12345", matching).block();
        service.invalidate(LOGOUT_REQUEST).block();

        assertTrue(manager.getSessionStore().retrieveSession(matching.getId()).blockOptional().isEmpty());
        assertTrue(manager.getSessionStore().retrieveSession(other.getId()).blockOptional().isPresent());
        service.invalidate(LOGOUT_REQUEST).block();
    }

    @Test
    void rejectsMalformedAndEntityExpandingLogoutMessages() {
        assertEquals("ST-12345", CasSingleLogoutService.parseSessionIndex(LOGOUT_REQUEST));
        assertThrows(IllegalArgumentException.class,
                () -> CasSingleLogoutService.parseSessionIndex("<LogoutRequest/>"));
        assertThrows(IllegalArgumentException.class,
                () -> CasSingleLogoutService.parseSessionIndex("""
                        <!DOCTYPE x [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                        <samlp:LogoutRequest xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol">
                          <samlp:SessionIndex>&secret;</samlp:SessionIndex>
                        </samlp:LogoutRequest>
                        """));
    }
}
