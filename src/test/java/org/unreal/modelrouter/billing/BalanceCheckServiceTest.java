package org.unreal.modelrouter.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.billing.freequota.FreeQuotaService;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BalanceCheckService 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("余额校验服务测试")
class BalanceCheckServiceTest {
    @Test void missingCurrentBillingAccountCannotFallBackToAnOldSessionCompany() {
        var snapshot=new DepartmentUsageSnapshot("request","code:D1","学院","PERSONAL",false,"PLATFORM",0,null,1,DepartmentBudgetService.month(),null,null,null,null);
        var identity=new UserIdentity("u1","u1",null,null,null,null,"old-company",true,1,1L,4,true).withUsageSnapshot(snapshot);
        StepVerifier.create(balanceCheckService.checkBalance(identity,"chat"))
                .expectErrorMatches(error->error instanceof ResponseStatusException r && r.getStatusCode()==HttpStatus.FORBIDDEN).verify();
        org.mockito.Mockito.verifyNoInteractions(enterpriseLookupService);
    }

    @Mock
    private EnterpriseLookupService enterpriseLookupService;

    @Mock
    private FreeQuotaService freeQuotaService;

    @Mock private org.unreal.modelrouter.billing.UserUsagePolicyService usagePolicyService;

    @InjectMocks
    private BalanceCheckService balanceCheckService;

    // ========== 实名认证相关测试 ==========

    @Test
    @DisplayName("平台用户企业已实名且余额充足 -> 放行")
    void enterpriseRealNameAuthenticated_shouldPass() {
        UserIdentity identity = new UserIdentity(
                "u1", "u1@example.com", "1", "key",
                1L, "ent", "c1", true, 1, 100L, 4);
        when(enterpriseLookupService.lookupByAccount("c1", 1))
                .thenReturn(Optional.of(infoWithBalance(BigDecimal.TEN)));

        StepVerifier.create(balanceCheckService.checkBalance(identity, "chat"))
                .verifyComplete();
    }

    @Test
    @DisplayName("平台用户个人已实名无企业 -> 有免费额度仍校验余额")
    void personalRealNameAuthenticated_withFreeQuota_shouldPass() {
        UserIdentity identity = new UserIdentity(
                "u1", "u1@example.com", "1", "key",
                null, null, null, true, 2, 100L, 2);

        when(enterpriseLookupService.lookupByAccount("u1", 2)).thenReturn(Optional.of(infoWithBalance(BigDecimal.TEN)));
        StepVerifier.create(balanceCheckService.checkBalance(identity, "chat"))
                .verifyComplete();

        verify(enterpriseLookupService).lookupByAccount("u1", 2);
        org.mockito.Mockito.verifyNoInteractions(freeQuotaService);
    }

    @Test
    @DisplayName("平台用户个人已实名无企业 -> 免费额度耗尽但余额充足时放行")
    void personalRealNameAuthenticated_freeQuotaExhausted_withBalance_shouldPass() {
        UserIdentity identity = new UserIdentity(
                "u1", "u1@example.com", "1", "key",
                null, null, null, true, 2, 100L, 2);
        when(enterpriseLookupService.lookupByAccount("u1", 2))
                .thenReturn(Optional.of(infoWithBalance(BigDecimal.TEN)));

        StepVerifier.create(balanceCheckService.checkBalance(identity, "chat"))
                .verifyComplete();
    }

    @Test
    @DisplayName("平台用户个人已实名无企业 -> 免费额度耗尽且余额不足返回 402")
    void personalRealNameAuthenticated_freeQuotaExhausted_balanceInsufficient_shouldReturnPaymentRequired() {
        UserIdentity identity = new UserIdentity(
                "u1", "u1@example.com", "1", "key",
                null, null, null, true, 2, 100L, 2);
        when(enterpriseLookupService.lookupByAccount("u1", 2))
                .thenReturn(Optional.of(infoWithBalance(new BigDecimal("-1.00"))));

        StepVerifier.create(balanceCheckService.checkBalance(identity, "chat"))
                .expectErrorSatisfies(throwable -> {
                    ResponseStatusException rse = (ResponseStatusException) throwable;
                    assert rse.getStatusCode() == HttpStatus.PAYMENT_REQUIRED;
                    assert rse.getReason().contains("余额不足");
                })
                .verify();
    }

    @Test
    @DisplayName("平台用户未实名 -> 返回 403")
    void platformUserNotRealName_shouldReturnForbidden() {
        UserIdentity identity = new UserIdentity(
                "user-1", "account-1", "key-1", "测试Key",
                null, null, null, true, 0, 100L, 0);

        StepVerifier.create(balanceCheckService.checkBalance(identity, "chat"))
                .expectErrorSatisfies(throwable -> {
                    ResponseStatusException rse = (ResponseStatusException) throwable;
                    assert rse.getStatusCode() == HttpStatus.FORBIDDEN;
                    assert rse.getReason().contains("未实名认证");
                })
                .verify();

        verify(enterpriseLookupService, never()).lookupByAccount(anyString(), anyInt());
    }

    @Test
    @DisplayName("平台用户实名状态为空 -> 返回 403")
    void platformUserNullVerifyStatus_shouldReturnForbidden() {
        UserIdentity identity = new UserIdentity(
                "user-1", "account-1", "key-1", "测试Key",
                1L, "ent", "c1", true, 1, 100L, null);

        StepVerifier.create(balanceCheckService.checkBalance(identity, "chat"))
                .expectErrorSatisfies(throwable -> {
                    ResponseStatusException rse = (ResponseStatusException) throwable;
                    assert rse.getStatusCode() == HttpStatus.FORBIDDEN;
                })
                .verify();
    }

    @Test
    @DisplayName("平台用户 user_type 与 verify_status 不匹配 -> 返回 403")
    void platformUserMismatchedRealName_shouldReturnForbidden() {
        UserIdentity identity = new UserIdentity(
                "user-1", "account-1", "key-1", "测试Key",
                1L, "ent", "c1", true, 1, 100L, 2);

        StepVerifier.create(balanceCheckService.checkBalance(identity, "chat"))
                .expectErrorSatisfies(throwable -> {
                    ResponseStatusException rse = (ResponseStatusException) throwable;
                    assert rse.getStatusCode() == HttpStatus.FORBIDDEN;
                })
                .verify();
    }

    // ========== 免费额度测试 ==========

    @Test
    @DisplayName("企业用户有免费额度 -> 仍校验余额")
    void enterpriseUserWithFreeQuota_shouldPass() {
        UserIdentity identity = new UserIdentity(
                "u1", "u1@example.com", "1", "key",
                1L, "ent", "c1", true, 1, 100L, 4);

        when(enterpriseLookupService.lookupByAccount("c1", 1)).thenReturn(Optional.of(infoWithBalance(BigDecimal.TEN)));
        StepVerifier.create(balanceCheckService.checkBalance(identity, "chat"))
                .verifyComplete();

        verify(enterpriseLookupService).lookupByAccount("c1", 1);
        org.mockito.Mockito.verifyNoInteractions(freeQuotaService);
    }

    // ========== 余额检查测试 ==========

    @Test
    @DisplayName("企业用户余额不足 -> 返回 402")
    void enterpriseUserWithNegativeBalance_shouldReturnPaymentRequired() {
        UserIdentity identity = new UserIdentity(
                "user-1", "account-1", "key-1", "测试Key",
                1L, "企业A", "company-1", true, 1, 100L, 4);

        EnterpriseLookupService.EnterpriseInfo info = new EnterpriseLookupService.EnterpriseInfo();
        info.setEnterpriseId(1L);
        info.setBalance(new BigDecimal("-1.00"));

        when(enterpriseLookupService.lookupByAccount("company-1", 1)).thenReturn(Optional.of(info));

        StepVerifier.create(balanceCheckService.checkBalance(identity, "chat"))
                .expectErrorSatisfies(throwable -> {
                    ResponseStatusException rse = (ResponseStatusException) throwable;
                    assert rse.getStatusCode() == HttpStatus.PAYMENT_REQUIRED;
                    assert rse.getReason().contains("余额不足");
                })
                .verify();
    }

    @Test
    @DisplayName("企业用户余额为 0 -> 返回 402")
    void enterpriseUserWithZeroBalance_shouldReturnPaymentRequired() {
        UserIdentity identity = new UserIdentity(
                "user-1", "account-1", "key-1", "测试Key",
                1L, "企业A", "company-1", true, 1, 100L, 4);

        EnterpriseLookupService.EnterpriseInfo info = new EnterpriseLookupService.EnterpriseInfo();
        info.setEnterpriseId(1L);
        info.setBalance(BigDecimal.ZERO);

        when(enterpriseLookupService.lookupByAccount("company-1", 1)).thenReturn(Optional.of(info));

        StepVerifier.create(balanceCheckService.checkBalance(identity, "chat"))
                .expectErrorSatisfies(throwable -> {
                    ResponseStatusException rse = (ResponseStatusException) throwable;
                    assert rse.getStatusCode() == HttpStatus.PAYMENT_REQUIRED;
                    assert rse.getReason().contains("余额不足");
                })
                .verify();
    }

    @Test
    @DisplayName("企业用户账户不存在 -> 返回 402")
    void enterpriseUserWithEmptyAccount_shouldReturnPaymentRequired() {
        UserIdentity identity = new UserIdentity(
                "user-1", "account-1", "key-1", "测试Key",
                1L, "企业A", "company-1", true, 1, 100L, 4);

        when(enterpriseLookupService.lookupByAccount("company-1", 1)).thenReturn(Optional.empty());

        StepVerifier.create(balanceCheckService.checkBalance(identity, "chat"))
                .expectErrorSatisfies(throwable -> {
                    ResponseStatusException rse = (ResponseStatusException) throwable;
                    assert rse.getStatusCode() == HttpStatus.PAYMENT_REQUIRED;
                    assert rse.getReason().contains("余额不足");
                })
                .verify();
    }

    // ========== 本地用户测试 ==========

    @Test
    @DisplayName("本地 API Key 用户 -> 跳过校验")
    void localApiKeyUser_shouldPass() {
        UserIdentity identity = new UserIdentity(
                "key-1", "api_key_user", "key-1", "本地Key",
                null, null, null, false, null, null, null);

        StepVerifier.create(balanceCheckService.checkBalance(identity, "chat"))
                .verifyComplete();

        verify(enterpriseLookupService, never()).lookupByAccount(anyString(), anyInt());
    }

    @Test
    @DisplayName("identity 为 null -> 跳过校验")
    void nullIdentity_shouldPass() {
        StepVerifier.create(balanceCheckService.checkBalance(null, "chat"))
                .verifyComplete();

        verify(enterpriseLookupService, never()).lookupByAccount(anyString(), anyInt());
    }

    @Test
    void nullBalanceDoesNotAllowCalling() {
        UserIdentity identity = new UserIdentity("u1", "account", "k1", "key", null, null, null, true, 2, 100L, 2);
        when(enterpriseLookupService.lookupByAccount("u1", 2)).thenReturn(Optional.of(infoWithBalance(null)));
        StepVerifier.create(balanceCheckService.checkBalance(identity, "chat"))
                .expectErrorSatisfies(e -> org.junit.jupiter.api.Assertions.assertEquals(HttpStatus.PAYMENT_REQUIRED, ((ResponseStatusException)e).getStatusCode())).verify();
        org.mockito.Mockito.verifyNoInteractions(freeQuotaService);
    }

    @Test
    void legacyUnrestrictedCampusSessionCannotSkipBalance() {
        var identity = new UserIdentity("u1", "account", null, null, null, null, null, false, null, 100L, 0);
        StepVerifier.create(balanceCheckService.checkBalance(identity, "chat"))
                .expectError(ResponseStatusException.class).verify();
    }

    private EnterpriseLookupService.EnterpriseInfo infoWithBalance(BigDecimal balance) {
        EnterpriseLookupService.EnterpriseInfo info = new EnterpriseLookupService.EnterpriseInfo();
        info.setEnterpriseId(1L);
        info.setBalance(balance);
        return info;
    }

    @Test
    void campusLoginWithPositiveBalancePassesWithoutRealNameVerification() {
        var identity = new UserIdentity("cas:owner", "campus-user", "key", "app",
                null, null, null, true, 2, 100L, 0, true);
        when(enterpriseLookupService.lookupByAccount("cas:owner", 2))
                .thenReturn(Optional.of(infoWithBalance(new BigDecimal("100"))));
        StepVerifier.create(balanceCheckService.checkBalance(identity, "chat")).verifyComplete();
        verify(enterpriseLookupService).lookupByAccount("cas:owner", 2);
    }

    @Test
    void campusLoginStillRejectsZeroNegativeAndMissingBalances() {
        var identity = new UserIdentity("cas:owner", "campus-user", "key", "app",
                null, null, null, true, 2, 100L, 0, true);
        for (var balance : java.util.List.of(BigDecimal.ZERO, BigDecimal.ONE.negate())) {
            when(enterpriseLookupService.lookupByAccount("cas:owner", 2))
                    .thenReturn(Optional.of(infoWithBalance(balance)));
            StepVerifier.create(balanceCheckService.checkBalance(identity, "chat"))
                    .expectErrorSatisfies(error -> org.junit.jupiter.api.Assertions.assertEquals(
                            HttpStatus.PAYMENT_REQUIRED, ((ResponseStatusException) error).getStatusCode())).verify();
        }
        when(enterpriseLookupService.lookupByAccount("cas:owner", 2)).thenReturn(Optional.empty());
        StepVerifier.create(balanceCheckService.checkBalance(identity, "chat"))
                .expectErrorSatisfies(error -> org.junit.jupiter.api.Assertions.assertEquals(
                        HttpStatus.PAYMENT_REQUIRED, ((ResponseStatusException) error).getStatusCode())).verify();
    }
    @Test void administratorFreeCampusUserNeedsNoBalanceButRevocationIsImmediate() {
        var identity = new UserIdentity("u1", "campus", "key", "app", null, null, null, true, 2, 88L, 0, true);
        when(usagePolicyService.isFreeUsage("u1")).thenReturn(true, false);
        StepVerifier.create(balanceCheckService.checkBalance(identity, "chat")).verifyComplete();
        org.mockito.Mockito.verifyNoInteractions(enterpriseLookupService);
        when(enterpriseLookupService.lookupByAccount("u1", 2)).thenReturn(Optional.empty());
        StepVerifier.create(balanceCheckService.checkBalance(identity, "imgGen"))
                .expectErrorMatches(e -> e instanceof ResponseStatusException r && r.getStatusCode() == HttpStatus.PAYMENT_REQUIRED)
                .verify();
    }

    @Test void freePolicyDoesNotBypassExternalIdentityVerification() {
        var identity = new UserIdentity("u1", "external", "key", "app", null, null, null, true, 2, 88L, 0, false);
        StepVerifier.create(balanceCheckService.checkBalance(identity, "chat"))
                .expectErrorMatches(e -> e instanceof ResponseStatusException r && r.getStatusCode() == HttpStatus.FORBIDDEN).verify();
        org.mockito.Mockito.verifyNoInteractions(usagePolicyService, enterpriseLookupService);
    }

    @Test void freePolicyDatabaseFailureDoesNotGrantFreeAccess() {
        var identity = new UserIdentity("u1", "campus", "key", "app", null, null, null, true, 2, 88L, 0, true);
        when(usagePolicyService.isFreeUsage("u1")).thenThrow(new IllegalStateException("database unavailable"));
        StepVerifier.create(balanceCheckService.checkBalance(identity, "chat")).expectError(IllegalStateException.class).verify();
        org.mockito.Mockito.verifyNoInteractions(enterpriseLookupService);
    }
}
