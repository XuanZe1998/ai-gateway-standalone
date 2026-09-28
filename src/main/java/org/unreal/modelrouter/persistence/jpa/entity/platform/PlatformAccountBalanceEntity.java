package org.unreal.modelrouter.persistence.jpa.entity.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 映射算力平台 ai_account_balance 表。
 * 存储账户余额与预警配置，由 JAiRouter 写入余额扣减、last_warn_threshold 等预警状态。
 */
@Data
@Entity
@Table(name = "ai_account_balance")
public class PlatformAccountBalanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "account_id")
    private String accountId;

    @Column(name = "account_type")
    private Integer accountType;

    @Column(name = "balance")
    private BigDecimal balance;

    @Column(name = "warn_enabled")
    private Boolean warnEnabled;

    @Column(name = "warn_threshold")
    private BigDecimal warnThreshold;

    @Column(name = "last_warn_time")
    private LocalDateTime lastWarnTime;

    /** 上次预警阈值：NULL 表示当前在阈值上方；非 NULL 表示已针对该阈值触发预警且仍低于阈值 */
    @Column(name = "last_warn_threshold")
    private BigDecimal lastWarnThreshold;

    @Column(name = "creator")
    private String creator;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "updater")
    private String updater;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @Column(name = "deleted")
    private Boolean deleted;
}
