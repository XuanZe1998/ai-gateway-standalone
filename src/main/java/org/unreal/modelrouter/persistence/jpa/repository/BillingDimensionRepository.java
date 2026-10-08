package org.unreal.modelrouter.persistence.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.BillingDimensionEntity;

import java.util.List;
import java.util.Optional;

/** 计费维度定义 Repository（数据驱动维度管理）。 */
@Repository
public interface BillingDimensionRepository extends JpaRepository<BillingDimensionEntity, Long> {

    List<BillingDimensionEntity> findByGroupKeyOrderBySortOrderAsc(String groupKey);

    Optional<BillingDimensionEntity> findByGroupKeyAndDimensionKey(String groupKey, String dimensionKey);

    long countByGroupKey(String groupKey);
}