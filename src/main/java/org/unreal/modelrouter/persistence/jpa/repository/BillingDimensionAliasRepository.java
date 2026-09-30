package org.unreal.modelrouter.persistence.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.BillingDimensionAliasEntity;

import java.util.List;

/** 计费维度显示别名 Repository（按计费组全局配置）。 */
@Repository
public interface BillingDimensionAliasRepository extends JpaRepository<BillingDimensionAliasEntity, Long> {

    List<BillingDimensionAliasEntity> findByGroupKey(String groupKey);

    /** 全组替换（管理入口整组草稿提交时用） */
    void deleteByGroupKey(String groupKey);
}
