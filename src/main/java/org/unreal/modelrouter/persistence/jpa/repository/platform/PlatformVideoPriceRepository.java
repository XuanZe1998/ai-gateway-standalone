// 文件说明：PlatformVideoPriceRepository：负责网关业务中的数据访问。
package org.unreal.modelrouter.persistence.jpa.repository.platform;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformVideoPriceEntity;

import java.util.List;

@Repository
public interface PlatformVideoPriceRepository extends JpaRepository<PlatformVideoPriceEntity, Long> {

    /**
     * 查询指定模型的视频分辨率价格规则行（已过滤逻辑删除行；含停用行，计费侧过滤 enabled），
     * 按主键升序保证同分辨率重复行（平台数据异常）时命中结果确定。
     */
    List<PlatformVideoPriceEntity> findByModelIdAndDeletedFalseOrderById(Long modelId);
}
