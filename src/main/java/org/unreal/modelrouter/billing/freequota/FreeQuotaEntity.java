// 文件说明：FreeQuotaEntity：负责计费与余额管理中的组件实现。
package org.unreal.modelrouter.billing.freequota;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Data
@Entity
@DynamicUpdate
@Table(name = "ai_user_free_quota", indexes = {
    @Index(name = "idx_ai_user_free_quota_user_id", columnList = "user_id")
})
public class FreeQuotaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true, length = 64)
    private String userId;

    @Column(name = "total_quota", nullable = false)
    private Long totalQuota = 1_000_000L;

    @Column(name = "used_quota", nullable = false)
    private Long usedQuota = 0L;

    @Column(name = "remaining_quota", nullable = false)
    private Long remainingQuota = 1_000_000L;

    @Column(name = "quota_tier", nullable = false, length = 32)
    private String quotaTier = "STUDENT";

    @Column(name = "period_start", nullable = false)
    private LocalDateTime periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDateTime periodEnd;

    @Column(name = "trial_exhausted", nullable = false)
    private Boolean trialExhausted = false;

    @Column(name = "exhausted_at")
    private LocalDateTime exhaustedAt;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    @Column(name = "creator", length = 64)
    private String creator;

    @Column(name = "updater", length = 64)
    private String updater;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) createTime = now;
        if (updateTime == null) updateTime = now;
        if (deleted == null) deleted = false;
        if (totalQuota == null) totalQuota = 1_000_000L;
        if (usedQuota == null) usedQuota = 0L;
        if (remainingQuota == null) remainingQuota = totalQuota;
        if (quotaTier == null) quotaTier = "STUDENT";
        if (periodStart == null) periodStart = now.withDayOfMonth(1).toLocalDate().atStartOfDay();
        if (periodEnd == null) periodEnd = periodStart.plusMonths(1);
        if (trialExhausted == null) trialExhausted = false;
        if (version == null) version = 0L;
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
