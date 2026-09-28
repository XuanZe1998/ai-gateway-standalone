package org.unreal.modelrouter.auth.security.authentication;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.unreal.modelrouter.auth.security.audit.SecurityAuditService;
import org.unreal.modelrouter.auth.security.model.ApiKeyAuthentication;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.common.exception.SecurityAuthenticationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiKeyAuthenticationProviderTest {
    @Test
    void legacySynchronousProviderCannotResurrectOldConfigStoreKeys() {
        ApiKeyService old = mock(ApiKeyService.class);
        var provider = new ApiKeyAuthenticationProvider(old,
                mock(ApplicationEventPublisher.class), mock(SecurityAuditService.class));
        var error = assertThrows(SecurityAuthenticationException.class,
                () -> provider.authenticate(new ApiKeyAuthentication("old-key")));
        assertEquals("LEGACY_API_KEY_REVOKED", error.getErrorCode());
        verifyNoInteractions(old);
    }
}
