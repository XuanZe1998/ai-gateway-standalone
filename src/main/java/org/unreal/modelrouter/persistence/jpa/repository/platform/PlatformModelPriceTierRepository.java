package org.unreal.modelrouter.persistence.jpa.repository.platform;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformModelPriceTierEntity;

import java.util.List;

/**
 * ai_model_price_tier 阶梯计费档位 Repository（只读）。
 */
@Repository
public interface PlatformModelPriceTierRepository extends JpaRepository<PlatformModelPriceTierEntity, Long> {

    /**
     * 按模型 id 查询阶梯档位（按 tier_order 升序）。
     */
    List<PlatformModelPriceTierEntity> findByModelIdOrderByTierOrderAsc(Long modelId);
}
