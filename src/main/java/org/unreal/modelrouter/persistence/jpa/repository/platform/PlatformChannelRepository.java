// 文件说明：PlatformChannelRepository：负责网关业务中的数据访问。
package org.unreal.modelrouter.persistence.jpa.repository.platform;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformChannelEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlatformChannelRepository extends JpaRepository<PlatformChannelEntity, Long> {

    Optional<PlatformChannelEntity> findByNameAndDeletedFalse(String name);

    List<PlatformChannelEntity> findByName(String name);

    /**
     * 根据渠道 ID 查询（ai_model.channel_id 字段存的是渠道 ID，不是名称）
     */
    Optional<PlatformChannelEntity> findByIdAndDeletedFalse(Long id);
}
