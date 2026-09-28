package org.unreal.modelrouter.persistence.jpa.entity.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 映射算力平台 sldd_system_user_company 表。
 * 通过 companyId（即本表 id 主键）关联 ai_enterprise，获取企业真实名称。
 */
@Data
@Entity
@Table(name = "sldd_system_user_company")
public class PlatformUserCompanyEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "company_name")
    private String companyName;
}
