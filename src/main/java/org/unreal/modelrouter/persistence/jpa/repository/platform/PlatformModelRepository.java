// 文件说明：PlatformModelRepository：负责网关业务中的数据访问。
package org.unreal.modelrouter.persistence.jpa.repository.platform;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformModelEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlatformModelRepository extends JpaRepository<PlatformModelEntity, Long> {

    /**
     * 查询所有已上架的有效模型（deleted=false, status='1'）
     * 算力平台 ai_model.status：1=上架，2=下架/草稿
     */
    @Query("SELECT m FROM PlatformModelEntity m WHERE m.deleted = false AND m.status = '1'")
    List<PlatformModelEntity> findAllOnlineModels();

    /** @deprecated 使用 {@link #findAllOnlineModels()} 替代 */
    @Deprecated
    @Query("SELECT m FROM PlatformModelEntity m WHERE m.deleted = false AND (m.status IS NULL OR m.status <> :status)")
    List<PlatformModelEntity> findByDeletedFalseAndStatusNot(@Param("status") String status);

    Optional<PlatformModelEntity> findByRealNameAndDeletedFalse(String realName);

    List<PlatformModelEntity> findByChannelIdAndDeletedFalse(String channelId);
}
