// 文件说明：PlatformAccountBalanceRepository：负责网关业务中的数据访问。
package org.unreal.modelrouter.persistence.jpa.repository.platform;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformAccountBalanceEntity;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public interface PlatformAccountBalanceRepository extends JpaRepository<PlatformAccountBalanceEntity, Long> {

    /**
     * 通过 account_id + account_type 查找有效账户余额记录。
     */
    Optional<PlatformAccountBalanceEntity> findByAccountIdAndAccountTypeAndDeletedFalse(
            String accountId, Integer accountType);

    /**
     * 扣减余额，无条件执行（允许余额扣至负数）。
     * 余额校验已在请求前完成（BalanceCheckService：余额 < 0 时拒绝）。
     * 返回受影响行数：0 表示账户不存在或已删除。
     */
    @Modifying
    @Query("UPDATE PlatformAccountBalanceEntity b SET b.balance = b.balance - :cost, " +
           "b.updateTime = CURRENT_TIMESTAMP WHERE b.id = :id AND b.deleted = false")
    int deductBalance(@Param("id") Long id, @Param("cost") BigDecimal cost);

    /**
     * 直接从数据库读取真实余额（绕过 JPA 一级缓存）。
     * 用于扣减后获取准确的余额值，避免并发扣减导致快照不准。
     */
    @Query(value = "SELECT balance FROM ai_account_balance WHERE id = :id", nativeQuery = true)
    Optional<BigDecimal> findRealBalanceById(@Param("id") Long id);

    /**
     * 清除上次预警阈值，表示余额已回到阈值上方。
     */
    @Modifying
    @Query("UPDATE PlatformAccountBalanceEntity b SET b.lastWarnThreshold = NULL, " +
           "b.updateTime = CURRENT_TIMESTAMP WHERE b.id = :id AND b.lastWarnThreshold IS NOT NULL")
    int clearLastWarnThreshold(@Param("id") Long id);

    /**
     * 原子更新上次预警阈值与时间。
     * 满足以下任一条件即更新：
     * 1. last_warn_threshold 为空（首次预警）；
     * 2. 目标阈值与上次预警阈值不同（阈值被修改）；
     * 3. 扣减前余额高于上次预警阈值（余额曾回到阈值上方，如充值后再次下穿）。
     * 条件 3 兜住充值回正后再次下穿必须重新预警的场景。
     */
    @Modifying
    @Query("UPDATE PlatformAccountBalanceEntity b SET b.lastWarnThreshold = :threshold, " +
           "b.lastWarnTime = CURRENT_TIMESTAMP, b.updateTime = CURRENT_TIMESTAMP " +
           "WHERE b.id = :id AND (" +
           "  b.lastWarnThreshold IS NULL " +
           "  OR b.lastWarnThreshold != :threshold " +
           "  OR :balanceBeforeDeduction > b.lastWarnThreshold" +
           ")")
    int updateLastWarnThreshold(@Param("id") Long id,
                                @Param("threshold") BigDecimal threshold,
                                @Param("balanceBeforeDeduction") BigDecimal balanceBeforeDeduction);
}
