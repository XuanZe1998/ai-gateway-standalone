package org.unreal.modelrouter.auth.campus.key;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformSystemUserEntity;
import org.unreal.modelrouter.persistence.jpa.repository.CampusIdentityBindingRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformSystemUserRepository;
import org.unreal.modelrouter.platform.sync.PlatformDataSyncService;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CampusBillingIdentityTest {
    @Mock PlatformSystemUserRepository users;
    @Mock CampusIdentityBindingRepository bindings;
    @InjectMocks PlatformDataSyncService service;

    @Test void enabledCampusBindingBuildsUnverifiedBillingIdentity() {
        var user = new PlatformSystemUserEntity();
        user.setId(42L); user.setUserId("cas:" + "a".repeat(64));
        user.setUserType(2); user.setVerifyStatus(0);
        when(bindings.existsBySystemUserIdAndEnabledTrue(42L)).thenReturn(true);
        when(users.findById(42L)).thenReturn(Optional.of(user));
        var identity = service.getCampusUserIdentityBySystemUserId(42L, "campus-account").orElseThrow();
        assertEquals(user.getUserId(), identity.userId());
        assertTrue(identity.platformUser()); assertTrue(identity.campusAuthenticated());
        assertEquals(0, identity.verifyStatus());
        // Other platform credentials retain their existing verification requirement.
        assertTrue(service.getUserIdentityBySystemUserId(42L, "campus-account").isEmpty());
    }

    @Test void missingOrDisabledCampusBindingCannotGainCampusQualification() {
        when(bindings.existsBySystemUserIdAndEnabledTrue(42L)).thenReturn(false);
        assertTrue(service.getCampusUserIdentityBySystemUserId(42L, "campus-account").isEmpty());
        verifyNoInteractions(users);
    }

    @Test void missingUserCannotGainCampusQualification() {
        when(bindings.existsBySystemUserIdAndEnabledTrue(42L)).thenReturn(true);
        when(users.findById(42L)).thenReturn(Optional.empty());
        assertTrue(service.getCampusUserIdentityBySystemUserId(42L, "campus-account").isEmpty());
    }
}
