package org.unreal.modelrouter.auth.campus.key;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.unreal.modelrouter.auth.security.config.CustomReactiveAuthenticationManager;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.model.ApiKeyAuthentication;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.platform.sync.PlatformDataSyncService;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CampusKeyAuthenticationTest {
    private final ApiKeyService legacy = mock(ApiKeyService.class);
    private final CampusGatewayKeyService keys = mock(CampusGatewayKeyService.class);
    private final PlatformDataSyncService platform = mock(PlatformDataSyncService.class);
    private final SecurityProperties properties = new SecurityProperties();

    private CustomReactiveAuthenticationManager manager() {
        properties.getApiKey().setEnabled(true);
        return new CustomReactiveAuthenticationManager(legacy, null, properties, platform, null, null, keys);
    }

    @Test
    void newKeyRestoresOwnerBillingIdentityWithoutAdmin() {
        String secret = "gw2_test";
        when(keys.authenticate(secret)).thenReturn(new CampusGatewayKeyService.KeyView("key-1", 42L,
                "cas:account", "assignment", "ACTIVE", LocalDateTime.now(), LocalDateTime.now(), null));
        when(platform.getUserIdentityBySystemUserId(42L, "cas:account")).thenReturn(Optional.of(
                new UserIdentity("cas:account", "campus-user", null, null, null, null,
                        null, true, 2, 42L, 2)));
        Authentication result = manager().authenticate(new ApiKeyAuthentication(secret)).block();
        assertNotNull(result);
        assertTrue(result.isAuthenticated());
        assertFalse(result.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        assertTrue(result.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
        UserIdentity identity = (UserIdentity) result.getDetails();
        assertEquals("cas:account", identity.userId());
        assertEquals("key-1", identity.apiKeyId());
        assertTrue(identity.platformUser());
        verifyNoInteractions(legacy);
    }

    @Test
    void revokedNewKeyCannotFallThroughToExternalPlatform() {
        when(keys.authenticate("gw2_revoked")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> manager().authenticate(new ApiKeyAuthentication("gw2_revoked")).block());
        verifyNoInteractions(platform, legacy);
    }

    @Test
    void oldLocalKeyIsNotCheckedAgainstConfigStore() {
        when(platform.lookupApiKey("old-config-key"))
                .thenReturn(new PlatformDataSyncService.ApiKeyLookupResult(null, false, null));
        assertThrows(RuntimeException.class, () -> manager().authenticate(new ApiKeyAuthentication("old-config-key")).block());
        verifyNoInteractions(legacy);
        verify(platform).lookupApiKey("old-config-key");
    }

    @Test
    void externallyIssuedKeyStillWorks() {
        UserIdentity owner = new UserIdentity("platform-user", "alice", "external-id", "external",
                null, null, null, true, 2, 12L, 2);
        when(platform.lookupApiKey("external-key"))
                .thenReturn(new PlatformDataSyncService.ApiKeyLookupResult(owner, false, null));
        Authentication result = manager().authenticate(new ApiKeyAuthentication("external-key")).block();
        assertNotNull(result);
        assertEquals(owner, result.getDetails());
        verifyNoInteractions(legacy);
    }
}
