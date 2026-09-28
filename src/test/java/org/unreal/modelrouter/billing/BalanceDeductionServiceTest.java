// 文件说明：测试 BalanceDeductionServiceTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.unreal.modelrouter.billing.notification.NotificationService;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformAccountBalanceEntity;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformAccountBalanceRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BalanceDeductionServiceTest {

    @Mock
    private PlatformAccountBalanceRepository accountBalanceRepository;

    @Mock
    private NotificationService notificationService;

    private BalanceDeductionService service;

    private static final String ACCOUNT_ID = "company-1";
    private static final Integer ACCOUNT_TYPE = 1;
    private static final Long ACCOUNT_BALANCE_ID = 100L;

    @BeforeEach
    void setUp() {
        service = new BalanceDeductionService(accountBalanceRepository, notificationService);
    }

    private PlatformAccountBalanceEntity createAccountBalance(BigDecimal balance) {
        PlatformAccountBalanceEntity entity = new PlatformAccountBalanceEntity();
        entity.setId(ACCOUNT_BALANCE_ID);
        entity.setAccountId(ACCOUNT_ID);
        entity.setAccountType(ACCOUNT_TYPE);
        entity.setBalance(balance);
        return entity;
    }

    private EnterpriseLookupService.EnterpriseInfo createInfo(BigDecimal threshold, BigDecimal lastWarnThreshold) {
        EnterpriseLookupService.EnterpriseInfo info = new EnterpriseLookupService.EnterpriseInfo();
        info.setEnterpriseId(1L);
        info.setEnterpriseName("test-enterprise");
        info.setWarnEnabled(true);
        info.setWarnThreshold(threshold);
        info.setLastWarnThreshold(lastWarnThreshold);
        info.setUserId("10001");
        return info;
    }

    private void mockDeduction(BigDecimal cost, BigDecimal realBalance) {
        // 扣减前余额 = 扣减后余额 + 本次消费金额
        BigDecimal balanceBeforeDeduction = realBalance.add(cost);
        when(accountBalanceRepository.findByAccountIdAndAccountTypeAndDeletedFalse(ACCOUNT_ID, ACCOUNT_TYPE))
                .thenReturn(Optional.of(createAccountBalance(balanceBeforeDeduction)));
        when(accountBalanceRepository.deductBalance(ACCOUNT_BALANCE_ID, cost)).thenReturn(1);
        when(accountBalanceRepository.findRealBalanceById(ACCOUNT_BALANCE_ID)).thenReturn(Optional.of(realBalance));
        when(accountBalanceRepository.updateLastWarnThreshold(eq(ACCOUNT_BALANCE_ID), any(), any())).thenReturn(1);
        when(accountBalanceRepository.clearLastWarnThreshold(ACCOUNT_BALANCE_ID)).thenReturn(1);
    }

    @Test
    void shouldSendAlertWhenBalanceCrossesBelowThreshold() {
        // Given: 扣减前 2.1，扣减后 1.9，阈值 2.0，无历史预警
        BigDecimal cost = new BigDecimal("0.2");
        BigDecimal realBalance = new BigDecimal("1.9");
        BigDecimal threshold = new BigDecimal("2.0");
        EnterpriseLookupService.EnterpriseInfo info = createInfo(threshold, null);

        mockDeduction(cost, realBalance);

        // When
        service.deductAndAlert(ACCOUNT_ID, ACCOUNT_TYPE, cost, info);

        // Then
        verify(accountBalanceRepository).updateLastWarnThreshold(ACCOUNT_BALANCE_ID, threshold, realBalance.add(cost));
        verify(notificationService).sendLowBalanceAlert(info, realBalance);
        verify(accountBalanceRepository, never()).clearLastWarnThreshold(any());
    }

    @Test
    void shouldNotSendAlertWhenBalanceStaysBelowThreshold() {
        // Given: 持续低于，扣减前 1.5，扣减后 1.3，阈值 2.0，已预警过 2.0
        BigDecimal cost = new BigDecimal("0.2");
        BigDecimal realBalance = new BigDecimal("1.3");
        BigDecimal threshold = new BigDecimal("2.0");
        EnterpriseLookupService.EnterpriseInfo info = createInfo(threshold, threshold);

        mockDeduction(cost, realBalance);

        // When
        service.deductAndAlert(ACCOUNT_ID, ACCOUNT_TYPE, cost, info);

        // Then
        verify(accountBalanceRepository, never()).updateLastWarnThreshold(any(), any(), any());
        verify(notificationService, never()).sendLowBalanceAlert(any(), any());
        verify(accountBalanceRepository, never()).clearLastWarnThreshold(any());
    }

    @Test
    void shouldSendAlertAfterRechargeThenDropBelowThreshold() {
        // Given: 充值后余额 5.0，一次大扣减到 1.0，阈值 2.0，旧预警 2.0
        BigDecimal cost = new BigDecimal("4.0");
        BigDecimal realBalance = new BigDecimal("1.0");
        BigDecimal threshold = new BigDecimal("2.0");
        EnterpriseLookupService.EnterpriseInfo info = createInfo(threshold, threshold);

        mockDeduction(cost, realBalance);

        // When
        service.deductAndAlert(ACCOUNT_ID, ACCOUNT_TYPE, cost, info);

        // Then
        verify(accountBalanceRepository).updateLastWarnThreshold(ACCOUNT_BALANCE_ID, threshold, realBalance.add(cost));
        verify(notificationService).sendLowBalanceAlert(info, realBalance);
    }

    @Test
    void shouldSendAlertWhenRechargedJustAboveSameThresholdThenDropBelow() {
        // Given: 阈值未变，充值后余额刚好回到原阈值上方，再次下穿必须重新预警
        // 对应 bug 现场：balanceBeforeDeduction=465.763901, realBalance=457.123901, threshold=465, lastWarnThreshold=465
        BigDecimal cost = new BigDecimal("8.64");
        BigDecimal realBalance = new BigDecimal("457.123901");
        BigDecimal threshold = new BigDecimal("465.000000");
        BigDecimal balanceBeforeDeduction = realBalance.add(cost);
        EnterpriseLookupService.EnterpriseInfo info = createInfo(threshold, threshold);

        when(accountBalanceRepository.findByAccountIdAndAccountTypeAndDeletedFalse(ACCOUNT_ID, ACCOUNT_TYPE))
                .thenReturn(Optional.of(createAccountBalance(balanceBeforeDeduction)));
        when(accountBalanceRepository.deductBalance(ACCOUNT_BALANCE_ID, cost)).thenReturn(1);
        when(accountBalanceRepository.findRealBalanceById(ACCOUNT_BALANCE_ID)).thenReturn(Optional.of(realBalance));
        when(accountBalanceRepository.updateLastWarnThreshold(ACCOUNT_BALANCE_ID, threshold, balanceBeforeDeduction)).thenReturn(1);

        // When
        service.deductAndAlert(ACCOUNT_ID, ACCOUNT_TYPE, cost, info);

        // Then
        verify(accountBalanceRepository).updateLastWarnThreshold(ACCOUNT_BALANCE_ID, threshold, balanceBeforeDeduction);
        verify(notificationService).sendLowBalanceAlert(info, realBalance);
    }

    @Test
    void shouldNotSendAlertWhenBalanceStaysBelowSameThresholdWithoutRecharge() {
        // Given: 阈值未变，余额从未回到阈值上方，持续低于不应重复预警
        // balanceBeforeDeduction=465.073901, realBalance=457.783901, threshold=465, lastWarnThreshold=465
        BigDecimal cost = new BigDecimal("7.29");
        BigDecimal realBalance = new BigDecimal("457.783901");
        BigDecimal threshold = new BigDecimal("465.000000");
        BigDecimal balanceBeforeDeduction = realBalance.add(cost);
        EnterpriseLookupService.EnterpriseInfo info = createInfo(threshold, threshold);

        when(accountBalanceRepository.findByAccountIdAndAccountTypeAndDeletedFalse(ACCOUNT_ID, ACCOUNT_TYPE))
                .thenReturn(Optional.of(createAccountBalance(balanceBeforeDeduction)));
        when(accountBalanceRepository.deductBalance(ACCOUNT_BALANCE_ID, cost)).thenReturn(1);
        when(accountBalanceRepository.findRealBalanceById(ACCOUNT_BALANCE_ID)).thenReturn(Optional.of(realBalance));
        when(accountBalanceRepository.updateLastWarnThreshold(ACCOUNT_BALANCE_ID, threshold, balanceBeforeDeduction)).thenReturn(0);

        // When
        service.deductAndAlert(ACCOUNT_ID, ACCOUNT_TYPE, cost, info);

        // Then
        verify(accountBalanceRepository).updateLastWarnThreshold(ACCOUNT_BALANCE_ID, threshold, balanceBeforeDeduction);
        verify(notificationService, never()).sendLowBalanceAlert(any(), any());
    }

    @Test
    void shouldSendAlertWhenThresholdLoweredAndBalanceCrossesNewThreshold() {
        // Given: 阈值从 2.0 降到 1.4，余额从 1.5 掉到 1.3，旧预警 2.0
        BigDecimal cost = new BigDecimal("0.2");
        BigDecimal realBalance = new BigDecimal("1.3");
        BigDecimal newThreshold = new BigDecimal("1.4");
        EnterpriseLookupService.EnterpriseInfo info = createInfo(newThreshold, new BigDecimal("2.0"));

        mockDeduction(cost, realBalance);

        // When
        service.deductAndAlert(ACCOUNT_ID, ACCOUNT_TYPE, cost, info);

        // Then
        verify(accountBalanceRepository).updateLastWarnThreshold(ACCOUNT_BALANCE_ID, newThreshold, realBalance.add(cost));
        verify(notificationService).sendLowBalanceAlert(info, realBalance);
    }

    @Test
    void shouldNotSendAlertWhenThresholdLoweredButBalanceStillBelowBoth() {
        // Given: 阈值从 2.0 降到 1.4，余额从 1.0 掉到 0.9，旧预警 2.0
        BigDecimal cost = new BigDecimal("0.1");
        BigDecimal realBalance = new BigDecimal("0.9");
        BigDecimal newThreshold = new BigDecimal("1.4");
        EnterpriseLookupService.EnterpriseInfo info = createInfo(newThreshold, new BigDecimal("2.0"));

        mockDeduction(cost, realBalance);

        // When
        service.deductAndAlert(ACCOUNT_ID, ACCOUNT_TYPE, cost, info);

        // Then
        verify(accountBalanceRepository, never()).updateLastWarnThreshold(any(), any(), any());
        verify(notificationService, never()).sendLowBalanceAlert(any(), any());
    }

    @Test
    void shouldSendAlertWhenThresholdRaisedAndNoPriorAlert() {
        // Given: 阈值从 2.0 升到 3.0，余额原 2.5，扣减后 2.4，last_warn_threshold=NULL（首次/重置后）。
        BigDecimal cost = new BigDecimal("0.1");
        BigDecimal realBalance = new BigDecimal("2.4");
        BigDecimal newThreshold = new BigDecimal("3.0");
        EnterpriseLookupService.EnterpriseInfo info = createInfo(newThreshold, null);

        mockDeduction(cost, realBalance);

        // When
        service.deductAndAlert(ACCOUNT_ID, ACCOUNT_TYPE, cost, info);

        // Then
        verify(accountBalanceRepository).updateLastWarnThreshold(ACCOUNT_BALANCE_ID, newThreshold, realBalance.add(cost));
        verify(notificationService).sendLowBalanceAlert(info, realBalance);
    }

    @Test
    void shouldSendAlertWhenThresholdRaisedAndBalanceAboveOldThresholdWithPriorAlert() {
        // Given: 阈值从 2.0 升到 3.0，余额原 2.5（旧阈值线上方），扣减后 2.4（新阈值线下方），last_warn_threshold=旧阈值 2.0。
        BigDecimal cost = new BigDecimal("0.1");
        BigDecimal realBalance = new BigDecimal("2.4");
        BigDecimal newThreshold = new BigDecimal("3.0");
        EnterpriseLookupService.EnterpriseInfo info = createInfo(newThreshold, new BigDecimal("2.0"));

        mockDeduction(cost, realBalance);

        // When
        service.deductAndAlert(ACCOUNT_ID, ACCOUNT_TYPE, cost, info);

        // Then
        verify(accountBalanceRepository).updateLastWarnThreshold(ACCOUNT_BALANCE_ID, newThreshold, realBalance.add(cost));
        verify(notificationService).sendLowBalanceAlert(info, realBalance);
    }

    @Test
    void shouldNotSendAlertWhenThresholdRaisedAndBalanceWasBelowOldThreshold() {
        // Given: 阈值从 2.0 升到 3.0，余额原 1.0，扣减后 0.9，旧预警 2.0
        BigDecimal cost = new BigDecimal("0.1");
        BigDecimal realBalance = new BigDecimal("0.9");
        BigDecimal newThreshold = new BigDecimal("3.0");
        EnterpriseLookupService.EnterpriseInfo info = createInfo(newThreshold, new BigDecimal("2.0"));

        mockDeduction(cost, realBalance);

        // When
        service.deductAndAlert(ACCOUNT_ID, ACCOUNT_TYPE, cost, info);

        // Then
        verify(accountBalanceRepository, never()).updateLastWarnThreshold(any(), any(), any());
        verify(notificationService, never()).sendLowBalanceAlert(any(), any());
    }

    @Test
    void shouldClearLastWarnThresholdWhenBalanceAboveThreshold() {
        // Given: 余额 2.1，阈值 2.0，旧预警 2.0
        BigDecimal cost = new BigDecimal("0.1");
        BigDecimal realBalance = new BigDecimal("2.1");
        BigDecimal threshold = new BigDecimal("2.0");
        EnterpriseLookupService.EnterpriseInfo info = createInfo(threshold, threshold);

        mockDeduction(cost, realBalance);

        // When
        service.deductAndAlert(ACCOUNT_ID, ACCOUNT_TYPE, cost, info);

        // Then
        verify(accountBalanceRepository).clearLastWarnThreshold(ACCOUNT_BALANCE_ID);
        verify(accountBalanceRepository, never()).updateLastWarnThreshold(any(), any(), any());
        verify(notificationService, never()).sendLowBalanceAlert(any(), any());
    }

    @Test
    void shouldNotSendDuplicateAlertOnConcurrentDeduction() {
        // Given: 下穿场景，但 updateLastWarnThreshold 返回 0（已被其他线程抢占）
        BigDecimal cost = new BigDecimal("0.2");
        BigDecimal realBalance = new BigDecimal("1.9");
        BigDecimal threshold = new BigDecimal("2.0");
        EnterpriseLookupService.EnterpriseInfo info = createInfo(threshold, null);

        when(accountBalanceRepository.findByAccountIdAndAccountTypeAndDeletedFalse(ACCOUNT_ID, ACCOUNT_TYPE))
                .thenReturn(Optional.of(createAccountBalance(realBalance.add(cost))));
        when(accountBalanceRepository.deductBalance(ACCOUNT_BALANCE_ID, cost)).thenReturn(1);
        when(accountBalanceRepository.findRealBalanceById(ACCOUNT_BALANCE_ID)).thenReturn(Optional.of(realBalance));
        when(accountBalanceRepository.updateLastWarnThreshold(ACCOUNT_BALANCE_ID, threshold, realBalance.add(cost))).thenReturn(0);

        // When
        service.deductAndAlert(ACCOUNT_ID, ACCOUNT_TYPE, cost, info);

        // Then
        verify(accountBalanceRepository).updateLastWarnThreshold(ACCOUNT_BALANCE_ID, threshold, realBalance.add(cost));
        verify(notificationService, never()).sendLowBalanceAlert(any(), any());
    }

    @Test
    void shouldSkipWhenCostIsZeroOrNegative() {
        // Given
        EnterpriseLookupService.EnterpriseInfo info = createInfo(new BigDecimal("2.0"), null);

        // When
        service.deductAndAlert(ACCOUNT_ID, ACCOUNT_TYPE, BigDecimal.ZERO, info);
        service.deductAndAlert(ACCOUNT_ID, ACCOUNT_TYPE, new BigDecimal("-1.0"), info);

        // Then
        verifyNoInteractions(accountBalanceRepository);
        verifyNoInteractions(notificationService);
    }

    @Test
    void shouldSkipWhenWarnDisabled() {
        // Given: 余额下穿但预警未启用
        BigDecimal cost = new BigDecimal("0.2");
        BigDecimal realBalance = new BigDecimal("1.9");
        BigDecimal threshold = new BigDecimal("2.0");
        EnterpriseLookupService.EnterpriseInfo info = createInfo(threshold, null);
        info.setWarnEnabled(false);

        mockDeduction(cost, realBalance);

        // When
        service.deductAndAlert(ACCOUNT_ID, ACCOUNT_TYPE, cost, info);

        // Then
        verify(accountBalanceRepository, never()).updateLastWarnThreshold(any(), any(), any());
        verify(notificationService, never()).sendLowBalanceAlert(any(), any());
    }

    @Test
    void shouldSkipWhenThresholdIsNull() {
        // Given: 余额下穿但阈值未配置
        BigDecimal cost = new BigDecimal("0.2");
        BigDecimal realBalance = new BigDecimal("1.9");
        EnterpriseLookupService.EnterpriseInfo info = createInfo(null, null);

        mockDeduction(cost, realBalance);

        // When
        service.deductAndAlert(ACCOUNT_ID, ACCOUNT_TYPE, cost, info);

        // Then
        verify(accountBalanceRepository, never()).updateLastWarnThreshold(any(), any(), any());
        verify(notificationService, never()).sendLowBalanceAlert(any(), any());
    }

    @Test
    void shouldThrowWhenAccountBalanceNotFound() {
        // Given: 账户不存在
        BigDecimal cost = new BigDecimal("0.2");
        EnterpriseLookupService.EnterpriseInfo info = createInfo(new BigDecimal("2.0"), null);

        when(accountBalanceRepository.findByAccountIdAndAccountTypeAndDeletedFalse(ACCOUNT_ID, ACCOUNT_TYPE))
                .thenReturn(Optional.empty());

        // When / Then
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                service.deductAndAlert(ACCOUNT_ID, ACCOUNT_TYPE, cost, info));
        verify(accountBalanceRepository, never()).deductBalance(any(), any());
    }
}
