package org.unreal.modelrouter.persistence.jpa.repository.platform;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformUserCompanyEntity;

/**
 * sldd_system_user_company 只读查询。
 * id 即 ai_enterprise.company_id，用于获取企业真实名称。
 */
@Repository
public interface PlatformUserCompanyRepository extends JpaRepository<PlatformUserCompanyEntity, String> {
}
