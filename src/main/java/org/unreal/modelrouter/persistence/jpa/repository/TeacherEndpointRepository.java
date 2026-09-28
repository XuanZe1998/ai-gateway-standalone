// 文件说明：TeacherEndpointRepository：负责网关业务中的数据访问。
package org.unreal.modelrouter.persistence.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.unreal.modelrouter.persistence.jpa.entity.TeacherEndpointEntity;

import java.util.Optional;

public interface TeacherEndpointRepository extends JpaRepository<TeacherEndpointEntity, String> {
    Optional<TeacherEndpointEntity> findByEndpointIdAndEnabledTrue(String endpointId);

    Optional<TeacherEndpointEntity> findByIdentityBindingIdAndEnabledTrue(String identityBindingId);
}
