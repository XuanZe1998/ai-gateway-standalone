// 文件说明：PlatformEnterpriseRepository：负责网关业务中的数据访问。
package org.unreal.modelrouter.persistence.jpa.repository.platform;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformEnterpriseEntity;

import java.util.Optional;

@Repository
public interface PlatformEnterpriseRepository extends JpaRepository<PlatformEnterpriseEntity, Long> {

    /**
     * 通过 company_id 查找企业（关联链路：sldd_system_users.company_id → ai_enterprise.company_id）
     */
    Optional<PlatformEnterpriseEntity> findByCompanyIdAndDeletedFalse(String companyId);

}
