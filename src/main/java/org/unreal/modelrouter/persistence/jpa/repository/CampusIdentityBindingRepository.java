// 文件说明：CampusIdentityBindingRepository：负责网关业务中的数据访问。
package org.unreal.modelrouter.persistence.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.unreal.modelrouter.persistence.jpa.entity.CampusIdentityBindingEntity;

import java.util.Optional;

public interface CampusIdentityBindingRepository extends JpaRepository<CampusIdentityBindingEntity, String> {
    Optional<CampusIdentityBindingEntity> findByIdentityProviderAndExternalSubject(
            String identityProvider, String externalSubject);

    Optional<CampusIdentityBindingEntity> findByIdentityProviderAndExternalSubjectAndEnabledTrue(
            String identityProvider, String externalSubject);
}
