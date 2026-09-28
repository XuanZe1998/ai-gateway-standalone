// 文件说明：FreeQuotaService：负责计费与余额管理中的组件实现。
package org.unreal.modelrouter.billing.freequota;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FreeQuotaService {

    private static final String SYSTEM_UPDATER = "system";
    private static final int MAX_RETRIES = 3;

    private final FreeQuotaRepository repository;
    private final FreeQuotaProperties properties;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public long getRemainingQuota(String userId, String serviceType) {
        if (!isEnabledFor(userId, serviceType)) {
            return 0L;
        }
        resetExpiredQuota(userId);
        return repository.findByUserIdAndDeletedFalse(userId)
                .filter(q -> !Boolean.TRUE.equals(q.getTrialExhausted()))
                .map(FreeQuotaEntity::getRemainingQuota)
                .orElse(0L);
    }

    public boolean isEnabledFor(String userId, String serviceType) {
        return properties.isEnabled()
                && userId != null
                && properties.isFreeQuotaServiceType(serviceType);
    }

    /**
     * Idempotently provisions a monthly quota for a campus identity.
     * PostgreSQL ON CONFLICT makes concurrent logins on different replicas safe.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FreeQuotaEntity ensureQuota(final String userId, final Collection<String> roles) {
        if (!properties.isEnabled() || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("启用免费额度时 userId 不能为空");
        }
        FreeQuotaProperties.QuotaPolicy policy = properties.resolvePolicy(roles);
        QuotaPeriod period = currentPeriod();
        if (repository.findByUserIdAndDeletedFalse(userId).isEmpty()) {
            repository.createIfMissing(
                    userId,
                    policy.total(),
                    policy.tier(),
                    period.start(),
                    period.end(),
                    SYSTEM_UPDATER);
        }
        repository.updateQuotaPolicy(
                userId, policy.total(), policy.tier(), SYSTEM_UPDATER);
        repository.resetExpiredQuota(
                userId, LocalDateTime.now(zoneId()), period.start(), period.end(), SYSTEM_UPDATER);
        return repository.findByUserIdAndDeletedFalse(userId)
                .orElseThrow(() -> new IllegalStateException("用户额度初始化失败: " + userId));
    }

    /** Runs only on nodes where scheduling is enabled (the dedicated cluster worker). */
    @Scheduled(
            cron = "${jairouter.billing.free-quota.reset-cron:0 5 0 1 * *}",
            zone = "${jairouter.billing.free-quota.time-zone:Asia/Shanghai}")
    @Transactional
    public void resetMonthlyQuotas() {
        if (!properties.isEnabled()) {
            return;
        }
        QuotaPeriod period = currentPeriod();
        int updated = repository.resetAllExpiredQuotas(
                LocalDateTime.now(zoneId()), period.start(), period.end(), SYSTEM_UPDATER);
        log.info("月度免费额度重置完成, updated={}, periodStart={}, periodEnd={}",
                updated, period.start(), period.end());
    }

    /**
     * 扣减免费额度。
     * 当实际用量超过剩余额度时，会把剩余额度全部扣减并锁定，返回命中结果（业务上接受本次超支），
     * 不会抛异常截断响应。流式和非流式请求统一使用此策略。
     * <p>使用 REQUIRES_NEW 独立事务，确保扣减/锁定结果立即提交，不受调用方回滚影响。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FreeQuotaResult deductStreamingQuota(String userId, String serviceType, long totalTokens) {
        return doDeductQuota(userId, serviceType, totalTokens);
    }

    private FreeQuotaResult doDeductQuota(String userId, String serviceType, long totalTokens) {
        if (!isEnabledFor(userId, serviceType) || totalTokens <= 0) {
            return FreeQuotaResult.miss(0);
        }

        resetExpiredQuota(userId);

        int retries = 0;
        while (retries < MAX_RETRIES) {
            // 每次重试前清除 Hibernate 一级缓存，避免 findByUserId 命中旧版本对象，
            // 导致 version 永远是旧的、乐观锁冲突无法恢复。
            if (entityManager != null) {
                entityManager.clear();
            }

            Optional<FreeQuotaEntity> optional = repository.findByUserIdAndDeletedFalse(userId);
            if (optional.isEmpty()) {
                log.warn("免费额度记录不存在, userId={}, serviceType={}", userId, serviceType);
                return FreeQuotaResult.miss(0);
            }

            FreeQuotaEntity quota = optional.get();
            if (Boolean.TRUE.equals(quota.getTrialExhausted()) || quota.getRemainingQuota() <= 0) {
                log.info("免费额度已耗尽或已锁定, userId={}, remaining={}, trialExhausted={}",
                        userId, quota.getRemainingQuota(), quota.getTrialExhausted());
                return FreeQuotaResult.miss(quota.getRemainingQuota());
            }

            long remainingBefore = quota.getRemainingQuota();
            long currentVersion = quota.getVersion();

            if (totalTokens <= remainingBefore) {
                int rows = repository.deductQuota(userId, totalTokens, SYSTEM_UPDATER, currentVersion);
                if (rows > 0) {
                    log.info("免费额度扣减成功, userId={}, tokens={}, remainingBefore={}, remainingAfter={}",
                            userId, totalTokens, remainingBefore, remainingBefore - totalTokens);
                    return FreeQuotaResult.hit(totalTokens, remainingBefore, remainingBefore - totalTokens);
                }
                log.warn("免费额度扣减乐观锁冲突, userId={}, tokens={}, version={}, retry={}",
                        userId, totalTokens, currentVersion, retries + 1);
            } else {
                // 超支：扣减剩余全部并锁定，业务上接受本次超支
                int rows = repository.deductAndExhaust(userId, remainingBefore, LocalDateTime.now(), SYSTEM_UPDATER, currentVersion);
                if (rows > 0) {
                    log.warn("免费额度超支接受, userId={}, totalTokens={}, remaining={}, deducted={}",
                            userId, totalTokens, remainingBefore, remainingBefore);
                    return FreeQuotaResult.hit(remainingBefore, remainingBefore, 0);
                }
                log.warn("免费额度超支扣减乐观锁冲突, userId={}, totalTokens={}, version={}, retry={}",
                        userId, totalTokens, currentVersion, retries + 1);
            }

            retries++;
        }

        log.error("免费额度扣减重试耗尽, userId={}, totalTokens={}", userId, totalTokens);
        return FreeQuotaResult.miss(0);
    }

    private void resetExpiredQuota(final String userId) {
        QuotaPeriod period = currentPeriod();
        repository.resetExpiredQuota(
                userId, LocalDateTime.now(zoneId()), period.start(), period.end(), SYSTEM_UPDATER);
    }

    private QuotaPeriod currentPeriod() {
        LocalDate firstDay = LocalDate.now(zoneId()).withDayOfMonth(1);
        return new QuotaPeriod(firstDay.atStartOfDay(), firstDay.plusMonths(1).atStartOfDay());
    }

    private ZoneId zoneId() {
        String configured = properties.getTimeZone();
        return ZoneId.of(configured == null || configured.isBlank() ? "Asia/Shanghai" : configured);
    }

    private record QuotaPeriod(LocalDateTime start, LocalDateTime end) {
    }
}
