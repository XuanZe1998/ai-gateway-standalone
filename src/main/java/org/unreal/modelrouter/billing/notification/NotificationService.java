package org.unreal.modelrouter.billing.notification;

import org.unreal.modelrouter.billing.EnterpriseLookupService;

import java.math.BigDecimal;

/**
 * 通知服务接口。
 * 实现类负责将低余额预警发送到外部系统（如算力平台的短信服务）。
 */
public interface NotificationService {

    /**
     * 发送低余额预警通知。
     *
     * @param enterprise   账户信息（企业或个人，通过 accountType 区分）
     * @param currentBalance 扣减后的当前余额
     */
    void sendLowBalanceAlert(EnterpriseLookupService.EnterpriseInfo enterprise, BigDecimal currentBalance);
}
