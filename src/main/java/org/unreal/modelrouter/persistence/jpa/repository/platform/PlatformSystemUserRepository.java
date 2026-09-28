package org.unreal.modelrouter.persistence.jpa.repository.platform;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformSystemUserEntity;

import java.util.Optional;

/**
 * 映射 sldd_system_users 表；CAS 自动开户仅写入 cas: 命名空间。
 * 关联链路：ai_api_key.user_id → sldd_system_users.id(PK) → company_id → ai_enterprise
 */
@Repository
public interface PlatformSystemUserRepository extends JpaRepository<PlatformSystemUserEntity, Long> {
    // 使用继承的 findById(Long id) 按主键查询
    // ai_api_key.user_id 对应此表的 id（主键），不是 user_id 字段

    /**
     * 通过 user_id 字段查询（UserIdentity.userId 对应此列）
     */
    Optional<PlatformSystemUserEntity> findByUserId(String userId);
}
