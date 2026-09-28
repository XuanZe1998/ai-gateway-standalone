// 文件说明：PlatformApiKeyRepository：负责网关业务中的数据访问。
package org.unreal.modelrouter.persistence.jpa.repository.platform;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformApiKeyEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlatformApiKeyRepository extends JpaRepository<PlatformApiKeyEntity, Long> {

    /**
     * 查询所有未删除的 API Key（用于遍历解密比对）
     */
    List<PlatformApiKeyEntity> findByDeletedFalse();

    /**
     * 通过密钥哈希查询（已弃用：算力平台 key_hash 实际为 AES 密文，不再用哈希匹配）
     */
    @Deprecated
    Optional<PlatformApiKeyEntity> findByKeyHashAndDeletedFalse(String keyHash);

    Optional<PlatformApiKeyEntity> findByUserAccountAndDeletedFalse(String userAccount);
}
