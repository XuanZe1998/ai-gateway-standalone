// 文件说明：TeacherTrustedDeviceRepository：负责网关业务中的数据访问。
package org.unreal.modelrouter.persistence.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.unreal.modelrouter.persistence.jpa.entity.TeacherTrustedDeviceEntity;

import java.util.List;
import java.util.Optional;

public interface TeacherTrustedDeviceRepository extends JpaRepository<TeacherTrustedDeviceEntity, String> {
    Optional<TeacherTrustedDeviceEntity> findByIdentityBindingIdAndDeviceIdAndStatus(
            String identityBindingId, String deviceId, String status);

    Optional<TeacherTrustedDeviceEntity> findByIdentityBindingIdAndDeviceId(
            String identityBindingId, String deviceId);

    Optional<TeacherTrustedDeviceEntity> findByIdentityBindingIdAndCertificateFingerprintAndStatus(
            String identityBindingId, String certificateFingerprint, String status);

    long countByIdentityBindingIdAndStatus(String identityBindingId, String status);

    List<TeacherTrustedDeviceEntity> findByIdentityBindingIdOrderByCreatedAtDesc(String identityBindingId);
}
