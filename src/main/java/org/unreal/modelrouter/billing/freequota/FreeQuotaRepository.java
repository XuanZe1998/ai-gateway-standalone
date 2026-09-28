// 文件说明：FreeQuotaRepository：负责计费与余额管理中的组件实现。
package org.unreal.modelrouter.billing.freequota;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface FreeQuotaRepository extends JpaRepository<FreeQuotaEntity, Long> {

    Optional<FreeQuotaEntity> findByUserIdAndDeletedFalse(String userId);

    @Modifying
    @Query(value = "INSERT INTO ai_user_free_quota "
            + "(user_id, total_quota, used_quota, remaining_quota, quota_tier, period_start, period_end, "
            + "trial_exhausted, create_time, update_time, creator, updater, deleted, version) "
            + "VALUES (:userId, :total, 0, :total, :tier, :periodStart, :periodEnd, false, "
            + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, :updater, :updater, false, 0) "
            + "ON CONFLICT (user_id) DO NOTHING", nativeQuery = true)
    int createIfMissing(@Param("userId") String userId,
                        @Param("total") long total,
                        @Param("tier") String tier,
                        @Param("periodStart") LocalDateTime periodStart,
                        @Param("periodEnd") LocalDateTime periodEnd,
                        @Param("updater") String updater);

    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE ai_user_free_quota SET quota_tier = :tier, total_quota = :total, "
            + "remaining_quota = GREATEST(:total - used_quota, 0), "
            + "trial_exhausted = CASE WHEN used_quota >= :total THEN true ELSE false END, "
            + "exhausted_at = CASE WHEN used_quota >= :total "
            + "THEN COALESCE(exhausted_at, CURRENT_TIMESTAMP) ELSE NULL END, "
            + "update_time = CURRENT_TIMESTAMP, updater = :updater, version = version + 1 "
            + "WHERE user_id = :userId AND deleted = false "
            + "AND (quota_tier IS DISTINCT FROM :tier OR total_quota <> :total)",
            nativeQuery = true)
    int updateQuotaPolicy(@Param("userId") String userId,
                          @Param("total") long total,
                          @Param("tier") String tier,
                          @Param("updater") String updater);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE FreeQuotaEntity q SET q.usedQuota = 0, q.remainingQuota = q.totalQuota, "
            + "q.trialExhausted = false, q.exhaustedAt = null, q.periodStart = :periodStart, "
            + "q.periodEnd = :periodEnd, q.updateTime = CURRENT_TIMESTAMP, q.updater = :updater, "
            + "q.version = q.version + 1 WHERE q.userId = :userId AND q.deleted = false "
            + "AND (q.periodEnd IS NULL OR q.periodEnd <= :now)")
    int resetExpiredQuota(@Param("userId") String userId,
                          @Param("now") LocalDateTime now,
                          @Param("periodStart") LocalDateTime periodStart,
                          @Param("periodEnd") LocalDateTime periodEnd,
                          @Param("updater") String updater);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE FreeQuotaEntity q SET q.usedQuota = 0, q.remainingQuota = q.totalQuota, "
            + "q.trialExhausted = false, q.exhaustedAt = null, q.periodStart = :periodStart, "
            + "q.periodEnd = :periodEnd, q.updateTime = CURRENT_TIMESTAMP, q.updater = :updater, "
            + "q.version = q.version + 1 WHERE q.deleted = false "
            + "AND (q.periodEnd IS NULL OR q.periodEnd <= :now)")
    int resetAllExpiredQuotas(@Param("now") LocalDateTime now,
                              @Param("periodStart") LocalDateTime periodStart,
                              @Param("periodEnd") LocalDateTime periodEnd,
                              @Param("updater") String updater);

    @Modifying
    @Query("UPDATE FreeQuotaEntity q SET q.usedQuota = q.usedQuota + :tokens, " +
           "q.remainingQuota = q.remainingQuota - :tokens, q.updateTime = CURRENT_TIMESTAMP, " +
           "q.updater = :updater, q.version = q.version + 1 " +
           "WHERE q.userId = :userId AND q.version = :version AND q.deleted = false")
    int deductQuota(@Param("userId") String userId,
                    @Param("tokens") long tokens,
                    @Param("updater") String updater,
                    @Param("version") long version);

    /**
     * 免费额度超支时一次性扣减剩余全部额度并标记锁定。
     */
    @Modifying
    @Query("UPDATE FreeQuotaEntity q SET q.usedQuota = q.usedQuota + :tokens, " +
           "q.remainingQuota = q.remainingQuota - :tokens, q.trialExhausted = true, " +
           "q.exhaustedAt = :exhaustedAt, q.updateTime = CURRENT_TIMESTAMP, " +
           "q.updater = :updater, q.version = q.version + 1 " +
           "WHERE q.userId = :userId AND q.version = :version AND q.deleted = false")
    int deductAndExhaust(@Param("userId") String userId,
                         @Param("tokens") long tokens,
                         @Param("exhaustedAt") LocalDateTime exhaustedAt,
                         @Param("updater") String updater,
                         @Param("version") long version);
}
