package org.unreal.modelrouter.persistence.jpa.entity.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 映射算力平台 ai_model_video_price 表（只读）— 视频模型分辨率价格规则。
 *
 * <p>仅视频模型（ai_model.model_type=3）有数据；单价单位由主表 ai_model.billing_unit 决定：
 * second=元/秒、token=元/M token。
 *
 * <p>has_video_input 语义：统一价格模式（price_mode=1）为 NULL（不区分视频输入）；
 * 按条件定价模式（price_mode=2）为 true=有视频输入 / false=无视频输入。
 */
@Data
@Entity
@Table(name = "ai_model_video_price")
public class PlatformVideoPriceEntity {

    @Id
    @Column(name = "id")
    private Long id;

    /** 关联 ai_model.id */
    @Column(name = "model_id")
    private Long modelId;

    /** 输出分辨率：480P / 720P / 1080P / 4K */
    @Column(name = "output_resolution")
    private String outputResolution;

    /** 是否有视频输入：统一价格模式为 NULL；按条件定价模式为 true=有 / false=无 */
    @Column(name = "has_video_input")
    private Boolean hasVideoInput;

    /** 规则启用标志：false=停用不参与计费，null 视为启用（默认 true） */
    @Column(name = "enabled")
    private Boolean enabled;

    /** 单价：单位由主表 ai_model.billing_unit 决定（second=元/秒，token=元/M token） */
    @Column(name = "price")
    private BigDecimal price;

    /** 逻辑删除标记：true=已删除（不参与计费），false=有效 */
    @Column(name = "deleted")
    private Boolean deleted;
}
