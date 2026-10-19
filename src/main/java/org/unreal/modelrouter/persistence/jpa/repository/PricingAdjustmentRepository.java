package org.unreal.modelrouter.persistence.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.unreal.modelrouter.persistence.jpa.entity.PricingAdjustmentEntity;

public interface PricingAdjustmentRepository extends JpaRepository<PricingAdjustmentEntity, String> {}
