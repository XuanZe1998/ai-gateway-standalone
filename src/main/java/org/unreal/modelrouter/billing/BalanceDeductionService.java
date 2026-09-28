package org.unreal.modelrouter.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.unreal.modelrouter.billing.notification.NotificationService;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformAccountBalanceEntity;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformAccountBalanceRepository;

import java.math.BigDecimal;

/**
 * 余额扣减 + 预警服务。
 * 在 BillingService.recordBilling() 异步计费后调用：
 * 1. 原子扣减 ai_account_balance 余额
 * 2. 检查是否触发低余额预警（基于"下穿阈值线"事件）
 * 3. 满足条件时发送通知
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceDeductionService {

    private final PlatformAccountBalanceRepository accountBalanceRepository;
    private final NotificationService notificationService;

    /**
     * 扣减余额并检查是否需要预警。
     * 整个方法在 @Async 线程中调用，@Transactional 保证扣减原子性。
     *
     * @param accountId    账户 ID（企业=company_id, 个人=user_id）
     * @param accountType  账户类型（1=企业, 2=个人）
     * @param cost         本次计费金额
     * @param info         账户信息（预警配置等）
     */
    @Transactional
    public void deductAndAlert(String accountId, Integer accountType, BigDecimal cost,
                               EnterpriseLookupService.EnterpriseInfo info) {
        if (accountId == null || accountId.isBlank() || accountType == null
                || cost == null || cost.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("跳过余额扣减, accountId={}, accountType={}, cost={}", accountId, accountType, cost);
            return;
        }

        // 1. 查询账户余额记录
        PlatformAccountBalanceEntity accountBalance = accountBalanceRepository
                .findByAccountIdAndAccountTypeAndDeletedFalse(accountId, accountType)
                .orElseThrow(() -> new IllegalStateException(
                        String.format("余额账户不存在, accountId=%s, accountType=%d", accountId, accountType)));

        // 记录扣减前余额，用于后续下穿判断（比 newBalance + cost 更可靠，不受并发影响）
        BigDecimal balanceBeforeDeduction = accountBalance.getBalance();

        // 2. 扣减余额（无条件执行，允许扣至负数；余额校验已在请求前完成）
        int rows = accountBalanceRepository.deductBalance(accountBalance.getId(), cost);
        if (rows == 0) {
            throw new IllegalStateException(
                    String.format("余额扣减失败（账户不存在或已删除）, accountId=%s, accountType=%d, cost=%s",
                            accountId, accountType, cost));
        }
        log.info("余额扣减成功, accountId={}, accountType={}, cost={}, rows={}, balanceBeforeDeduction={}",
                accountId, accountType, cost, rows, balanceBeforeDeduction);

        // 3. 扣减后直接从 DB 读取真实余额（原生查询绕过 JPA 一级缓存，避免并发快照不准）
        BigDecimal realBalance = accountBalanceRepository.findRealBalanceById(accountBalance.getId())
                .orElse(BigDecimal.ZERO);

        // 兜底：如果扣减前余额字段为空，用 newBalance + cost 反推
        if (balanceBeforeDeduction == null) {
            balanceBeforeDeduction = realBalance.add(cost);
        }

        // 4. 检查预警条件并原子更新状态
        if (shouldAlert(info, realBalance, balanceBeforeDeduction)) {
            try {
                int updated = accountBalanceRepository.updateLastWarnThreshold(
                        accountBalance.getId(), info.getWarnThreshold(), balanceBeforeDeduction);
                if (updated > 0) {
                    sendLowBalanceAlert(accountId, accountType, info, realBalance);
                } else {
                    log.info("低余额预警已被其他线程处理, accountId={}, accountType={}, balance={}, threshold={}",
                            accountId, accountType, realBalance, info.getWarnThreshold());
                }
            } catch (Exception e) {
                log.error("更新低余额预警状态失败, accountId={}, accountType={}: {}",
                        accountId, accountType, e.getMessage(), e);
            }
        } else if (info.getWarnThreshold() != null && realBalance.compareTo(info.getWarnThreshold()) >= 0) {
            // 余额回到阈值上方，清除预警状态
            try {
                int cleared = accountBalanceRepository.clearLastWarnThreshold(accountBalance.getId());
                log.info("余额回到阈值上方, 清除预警状态, accountId={}, accountType={}, balance={}, threshold={}, cleared={}",
                        accountId, accountType, realBalance, info.getWarnThreshold(), cleared);
            } catch (Exception e) {
                log.error("清除低余额预警状态失败, accountId={}, accountType={}: {}",
                        accountId, accountType, e.getMessage(), e);
            }
        }
    }

    private void sendLowBalanceAlert(String accountId, Integer accountType,
                                     EnterpriseLookupService.EnterpriseInfo info,
                                     BigDecimal realBalance) {
        try {
            notificationService.sendLowBalanceAlert(info, realBalance);
            log.info("低余额预警已触发, accountId={}, accountType={}, balance={}, threshold={}",
                    accountId, accountType, realBalance, info.getWarnThreshold());
        } catch (Exception e) {
            log.error("发送低余额预警失败, accountId={}, accountType={}: {}",
                    accountId, accountType, e.getMessage(), e);
        }
    }

    /**
     * 判断是否需要触发预警。
     * 核心规则：只在余额"下穿"当前阈值时触发一次。下穿有两种成因——余额下降，或阈值线上升。
     *
     * <p>触发条件（满足其一即可）：
     * <ul>
     *   <li>扣减前余额 >= 当前阈值 且 扣减后余额 < 当前阈值：本次扣减发生了下穿；</li>
     *   <li>last_warn_threshold 为 NULL：认为之前处于阈值上方（首次、充值后、或阈值提高后从未记录）；</li>
     *   <li>阈值被提高 且 扣减前余额在旧阈值线上方：阈值线上升把余额"兜"进了低余额区，
     *       等价于一次下穿（充值不走扣减链路，last_warn_threshold 会保留旧值，必须靠此条件兜住）。</li>
     * </ul>
     */
    private boolean shouldAlert(EnterpriseLookupService.EnterpriseInfo info, BigDecimal newBalance,
                                BigDecimal balanceBeforeDeduction) {
        log.info("进入低余额预警判断, accountId={}, balanceBeforeDeduction={}, newBalance={}, warnEnabled={}, warnThreshold={}, lastWarnThreshold={}",
                info.getCompanyId(), balanceBeforeDeduction, newBalance,
                info.getWarnEnabled(), info.getWarnThreshold(), info.getLastWarnThreshold());

        if (!Boolean.TRUE.equals(info.getWarnEnabled())) {
            log.info("预警未启用, accountId={}", info.getCompanyId());
            return false;
        }
        BigDecimal threshold = info.getWarnThreshold();
        if (threshold == null) {
            log.info("预警阈值未配置, accountId={}", info.getCompanyId());
            return false;
        }
        if (newBalance.compareTo(threshold) >= 0) {
            log.info("扣减后余额仍高于阈值, accountId={}, newBalance={}, threshold={}",
                    info.getCompanyId(), newBalance, threshold);
            return false;
        }

        BigDecimal lastWarnThreshold = info.getLastWarnThreshold();

        boolean crossedDown = balanceBeforeDeduction.compareTo(threshold) >= 0;
        boolean noRecordedAlert = lastWarnThreshold == null;
        boolean thresholdRaisedAboveBalance = lastWarnThreshold != null
                && threshold.compareTo(lastWarnThreshold) > 0
                && balanceBeforeDeduction.compareTo(lastWarnThreshold) >= 0;

        log.info("预警条件计算, accountId={}, crossedDown={}, noRecordedAlert={}, thresholdRaisedAboveBalance={}, balanceBeforeDeduction={}, threshold={}, lastWarnThreshold={}",
                info.getCompanyId(), crossedDown, noRecordedAlert, thresholdRaisedAboveBalance,
                balanceBeforeDeduction, threshold, lastWarnThreshold);

        if (crossedDown || noRecordedAlert || thresholdRaisedAboveBalance) {
            return true;
        }

        // 持续低于阈值（余额下降或阈值下降导致，但未发生新的下穿），不重复触发
        log.info("预警状态持续低于阈值, 不重复触发, accountId={}, balanceBeforeDeduction={}, newBalance={}, threshold={}, lastWarnThreshold={}",
                info.getCompanyId(), balanceBeforeDeduction, newBalance, threshold, lastWarnThreshold);
        return false;
    }
}
