package org.unreal.modelrouter.persistence.jpa.entity.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 映射算力平台 ai_user_model_discount 表（只读）。
 * 按 用户类型 + 用户ID + 模型ID 查询 用户×模型 独立折扣。
 */
@Data
@Entity
@Table(name = "ai_user_model_discount")
public class UserModelDiscountEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "user_type")
    private Integer userType;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "model_id")
    private Long modelId;

    @Column(name = "discount")
    private Integer discount;

    @Column(name = "deleted")
    private Boolean deleted;
}
