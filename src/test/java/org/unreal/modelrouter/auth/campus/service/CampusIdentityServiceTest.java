// 文件说明：测试 CampusIdentityServiceTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.auth.campus.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.unreal.modelrouter.auth.campus.config.CampusAuthProperties;
import org.unreal.modelrouter.auth.campus.model.CampusPrincipal;
import org.unreal.modelrouter.auth.campus.model.CasIdentity;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.billing.freequota.FreeQuotaService;
import org.unreal.modelrouter.common.exception.AuthenticationException;
import org.unreal.modelrouter.persistence.jpa.entity.CampusIdentityBindingEntity;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformSystemUserEntity;
import org.unreal.modelrouter.persistence.jpa.repository.CampusIdentityBindingRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformSystemUserRepository;
import org.unreal.modelrouter.platform.sync.PlatformDataSyncService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CampusIdentityServiceTest {
    @Mock
    private PlatformSystemUserRepository systemUserRepository;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PlatformDataSyncService platformDataSyncService;
    @Mock
    private CampusIdentityBindingRepository bindingRepository;
    @Mock
    private ObjectProvider<FreeQuotaService> freeQuotaProvider;

    private CampusAuthProperties properties;
    private CampusIdentityService service;

    @BeforeEach
    void setUp() {
        properties = new CampusAuthProperties();
        service = new CampusIdentityService(
                properties, systemUserRepository, jdbcTemplate, platformDataSyncService,
                bindingRepository, freeQuotaProvider);
    }

    @Test
    void prefersLocalAccountAndGrantsOnlyOrdinaryUser() {
        stubResolvedUser("T1001");

        CampusPrincipal principal = service.resolve(identity(
                "account", "teacher01", "localAccount", "T1001",
                "typeCode", "teacher-code", "name", "张老师"));

        assertEquals("T1001", principal.getName());
        assertEquals(List.of("USER"), principal.roles());
        assertTrue(principal.portals().containsAll(List.of("PLAYGROUND", "PROFILE")));
        assertFalse(principal.roles().contains("ADMIN"));
        verify(systemUserRepository).findById(1001L);
    }

    @Test
    void fallsBackToAccountWithoutTypeCode() {
        stubResolvedUser("student01");

        CampusPrincipal principal = service.resolve(identity(
                "account", "student01"));

        assertEquals("student01", principal.getName());
        assertEquals(List.of("USER"), principal.roles());
        ArgumentCaptor<CampusIdentityBindingEntity> bindingCaptor =
                ArgumentCaptor.forClass(CampusIdentityBindingEntity.class);
        verify(bindingRepository).save(bindingCaptor.capture());
        assertEquals("", bindingCaptor.getValue().getTypeCode());
    }

    @Test
    void administratorRequiresExplicitAccountAllowlist() {
        properties.setAdminAccounts(List.of("teacher01"));
        stubResolvedUser("T1001");

        CampusPrincipal principal = service.resolve(identity(
                "account", "teacher01", "localAccount", "T1001"));

        assertEquals(List.of("USER", "ADMIN"), principal.roles());
    }

    @Test
    void defaultAdminGrantsAdministratorToEveryCasUser() {
        properties.setDefaultAdmin(true);
        stubResolvedUser("student01");

        CampusPrincipal principal = service.resolve(identity("account", "student01"));

        assertEquals(List.of("USER", "ADMIN"), principal.roles());
        assertTrue(principal.portals().contains("ADMIN"));
    }

    @Test
    void typeCodeCannotGrantExtraRoles() {
        stubResolvedUser("user01");

        CampusPrincipal principal = service.resolve(identity(
                "account", "user01", "typeCode", "unsafe-admin-code"));

        assertEquals(List.of("USER"), principal.roles());
    }

    @Test
    void firstLoginCreatesUnverifiedUserWithoutMatchingImportedAccount() {
        stubNewUser();

        CampusPrincipal principal = service.resolve(identity("account", "student01"));

        ArgumentCaptor<PlatformSystemUserEntity> userCaptor =
                ArgumentCaptor.forClass(PlatformSystemUserEntity.class);
        verify(systemUserRepository).saveAndFlush(userCaptor.capture());
        assertTrue(userCaptor.getValue().getUserId().startsWith("cas:"));
        assertNotEquals("student01", userCaptor.getValue().getUserId());
        assertEquals(0, userCaptor.getValue().getVerifyStatus());
        assertEquals(List.of("USER"), principal.roles());
        assertEquals(0, principal.verifyStatus());
        assertNull(principal.enterpriseId());
        verify(systemUserRepository, never()).findByUserId("student01");

    }

    @Test
    void unrestrictedCasSessionCanUsePaidModelsWithoutLocalBalanceDeduction() {
        properties.setUnrestrictedAccess(true);
        stubNewUser();

        CampusPrincipal principal = service.resolve(identity("account", "student01"));

        assertTrue(principal.permissions().contains("CHAT"));
        assertFalse(principal.userIdentity().platformUser());
        assertNull(principal.userIdentity().userType());
        assertNull(principal.userIdentity().companyId());
        assertEquals(0, principal.verifyStatus());
        assertTrue(principal.userIdentity().userId().startsWith("cas:"));
        assertEquals(List.of("USER"), principal.roles());
    }

    @Test
    void unverifiedExistingBindingCanLogInButStaysUnverified() {
        PlatformSystemUserEntity user = platformUser("cas:preexisting");
        user.setVerifyStatus(0);
        stubBinding(user);
        when(platformDataSyncService.getUserIdentityBySystemUserId(1001L, "student01"))
                .thenReturn(Optional.empty());
        when(freeQuotaProvider.getIfAvailable()).thenReturn(null);

        CampusPrincipal principal = service.resolve(identity("account", "student01"));

        assertEquals(0, principal.verifyStatus());
        assertEquals("cas:preexisting", principal.platformUserId());
        verify(systemUserRepository, never()).saveAndFlush(any());
    }

    @Test
    void disabledBindingCannotProvisionReplacement() {
        CampusIdentityBindingEntity binding = existingBinding();
        binding.setEnabled(false);
        when(bindingRepository.findByIdentityProviderAndExternalSubject(
                "https://cas.ntit.edu.cn/cas", "cas-user-1")).thenReturn(Optional.of(binding));

        AuthenticationException error = assertThrows(AuthenticationException.class,
                () -> service.resolve(identity("account", "student01")));

        assertEquals("CAMPUS_IDENTITY_DISABLED", error.getErrorCode());
        verify(systemUserRepository, never()).saveAndFlush(any());
    }

    @Test
    void differentCasSubjectsDoNotShareAccountString() {
        stubNewUser();
        service.resolve(identity("account", "student01"));
        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        verify(systemUserRepository).findByUserId(idCaptor.capture());
        assertTrue(idCaptor.getValue().startsWith("cas:"));
    }

    private void stubNewUser() {
        when(bindingRepository.findByIdentityProviderAndExternalSubject(
                "https://cas.ntit.edu.cn/cas", "cas-user-1")).thenReturn(Optional.empty());
        when(systemUserRepository.findByUserId(any(String.class))).thenReturn(Optional.empty());
        when(systemUserRepository.saveAndFlush(any(PlatformSystemUserEntity.class)))
                .thenAnswer(invocation -> {
                    PlatformSystemUserEntity user = invocation.getArgument(0);
                    user.setId(1001L);
                    return user;
                });
        when(platformDataSyncService.getUserIdentityBySystemUserId(1001L, "student01"))
                .thenReturn(Optional.empty());
        when(bindingRepository.save(any(CampusIdentityBindingEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private CampusIdentityBindingEntity existingBinding() {
        return CampusIdentityBindingEntity.builder()
                .id("binding-1")
                .identityProvider("https://cas.ntit.edu.cn/cas")
                .externalSubject("cas-user-1")
                .systemUserId(1001L)
                .enabled(true)
                .build();
    }

    private void stubBinding(PlatformSystemUserEntity user) {
        when(bindingRepository.findByIdentityProviderAndExternalSubject(
                "https://cas.ntit.edu.cn/cas", "cas-user-1"))
                .thenReturn(Optional.of(existingBinding()));
        when(systemUserRepository.findById(1001L)).thenReturn(Optional.of(user));
        when(bindingRepository.save(any(CampusIdentityBindingEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubResolvedUser(final String userId) {
        PlatformSystemUserEntity user = platformUser(userId);
        stubBinding(user);
        when(platformDataSyncService.getUserIdentityBySystemUserId(1001L, userId))
                .thenReturn(Optional.of(platformIdentity(userId)));
        when(freeQuotaProvider.getIfAvailable()).thenReturn(null);
    }

    private PlatformSystemUserEntity platformUser(final String userId) {
        PlatformSystemUserEntity user = new PlatformSystemUserEntity();
        user.setId(1001L);
        user.setUserId(userId);
        user.setUsername(userId);
        return user;
    }

    private UserIdentity platformIdentity(final String userId) {
        return new UserIdentity(
                userId, userId, null, null,
                88L, "南通理工学院", "NTIT", true,
                1, 1001L, 2);
    }

    private CasIdentity identity(final String... values) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            attributes.put(values[i], values[i + 1]);
        }
        return new CasIdentity("cas-user-1", attributes);
    }
}


