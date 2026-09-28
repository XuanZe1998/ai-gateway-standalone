package org.unreal.modelrouter.persistence.jpa.entity.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 映射算力平台 ai_enterprise 表。
 * 当前仅保留企业白名单与折扣能力（subsidy_discount），余额与预警字段已迁移到 ai_account_balance。
 */
@Data
@Entity
@Table(name = "ai_enterprise")
public class PlatformEnterpriseEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "company_id")
    private String companyId;

    @Column(name = "description")
    private String description;

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

    /** 政府补贴折扣：百分比，如 95=95折，null=无折扣 */
    @Column(name = "subsidy_discount")
    private Integer subsidyDiscount;
}
