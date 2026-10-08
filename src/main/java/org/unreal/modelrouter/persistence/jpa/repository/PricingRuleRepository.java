package org.unreal.modelrouter.persistence.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.PricingRuleEntity;

import java.util.List;

/** 计费规则 Repository（数据驱动）。 */
@Repository
public interface PricingRuleRepository extends JpaRepository<PricingRuleEntity, Long> {

    List<PricingRuleEntity> findByModelIdOrderByPriorityAsc(Long modelId);

    List<PricingRuleEntity> findByModelIdAndEnabledTrueOrderByPriorityAsc(Long modelId);

    void deleteByModelId(Long modelId);

    long countByModelId(Long modelId);

    /** 统计被任意规则 price_json 引用的维度数量（维度删除前引用检查；jsonb_exists 等价于 ? 运算符） */
    @Query(value = "SELECT COUNT(*) FROM ai_model_price_rule WHERE jsonb_exists(price_json, :dimensionKey)", nativeQuery = true)
    long countRulesReferencingDimension(@Param("dimensionKey") String dimensionKey);
}