package org.unreal.modelrouter.billing.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.billing.EnterpriseLookupService;

import java.math.BigDecimal;

/**
 * 通知服务空实现（当 notification.enabled=false 时激活）。
 * 保证 BalanceDeductionService 的依赖注入不会失败。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "jairouter.billing.notification.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledNotificationService implements NotificationService {

    @Override
    public void sendLowBalanceAlert(EnterpriseLookupService.EnterpriseInfo enterprise,
                                    BigDecimal currentBalance) {
        log.debug("通知服务未启用，跳过预警: accountName={}, accountType={}, balance={}",
                enterprise.getEnterpriseName(), enterprise.getAccountType(), currentBalance);
    }
}
