// 文件说明：UserModelDiscountRepository：负责计费与余额管理中的数据访问。
package org.unreal.modelrouter.persistence.jpa.repository.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.billing.UserModelDiscountEntity;

import java.util.Optional;

@Repository
public interface UserModelDiscountRepository extends JpaRepository<UserModelDiscountEntity, Long> {

    /**
     * 按用户类型、用户ID、模型ID查询有效折扣记录。
     * 使用 findFirstBy 作为防御性兜底，避免极端情况下存在多条未删除记录时抛异常。
     */
    Optional<UserModelDiscountEntity> findFirstByUserTypeAndUserIdAndModelIdAndDeletedFalse(
            Integer userType, String userId, Long modelId);
}
