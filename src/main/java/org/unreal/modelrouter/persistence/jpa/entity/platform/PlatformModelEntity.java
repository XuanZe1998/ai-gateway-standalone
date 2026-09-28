package org.unreal.modelrouter.persistence.jpa.entity.platform;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 映射算力平台 ai_model 表（只读）
 */
@Data
@Entity
@Table(name = "ai_model")
public class PlatformModelEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "image")
    private String image;

    @Column(name = "alias")
    private String alias;

    @Column(name = "real_name")
    private String realName;

    @Column(name = "description")
    private String description;

    @Column(name = "vendor")
    private String vendor;

    @Column(name = "model_type")
    private String modelType;

    @Column(name = "channel_id")
    private String channelId;

    @Column(name = "support_thinking")
    private Boolean supportThinking;

    @Column(name = "support_tools")
    private Boolean supportTools;

    @Column(name = "input_price")
    private BigDecimal inputPrice;

    @Column(name = "output_price")
    private BigDecimal outputPrice;

    @Column(name = "discount")
    private Integer discount;

    // ===== 计费模式与计费项配置（算力平台新增字段，可能为 null，业务侧按默认值兜底）=====
    @Column(name = "billing_mode")
    private Integer billingMode; // 1=整体计费, 2=阶梯计费；null 默认 1

    @Column(name = "enable_input_token")
    private Boolean enableInputToken; // 普通输入(缓存未命中)是否计费；null 默认 true

    @Column(name = "enable_cache_hit_input")
    private Boolean enableCacheHitInput; // 缓存命中是否计费；null 默认 true

    @Column(name = "enable_output_token")
    private Boolean enableOutputToken; // 输出是否计费；null 默认 true

    @Column(name = "enable_cache_create_input")
    private Boolean enableCacheCreateInput; // 显式缓存创建是否计费；null 默认 false

    @Column(name = "enable_cache_hit_explicit_input")
    private Boolean enableCacheHitExplicitInput; // 显式缓存命中是否计费；null 默认 false

    @Column(name = "cache_hit_input_price")
    private BigDecimal cacheHitInputPrice; // 元/M token

    @Column(name = "cache_create_input_price")
    private BigDecimal cacheCreateInputPrice; // 元/M token

    @Column(name = "cache_hit_explicit_input_price")
    private BigDecimal cacheHitExplicitInputPrice; // 元/M token

    @Column(name = "thinking_billing_mode")
    private Integer thinkingBillingMode; // 1=计入输出token, 2=单独计费, 3=不计费；null 默认 1

    @Column(name = "thinking_price")
    private BigDecimal thinkingPrice; // 元/M token，仅 thinkingBillingMode=2 有效

    // ===== 视频模型计费元数据（model_type=3 专用，可能为 null，业务侧按默认值兜底）=====
    @Column(name = "price_mode")
    private Integer priceMode; // 1=统一价格, 2=按条件定价；null 默认 1

    @Column(name = "billing_unit")
    private String billingUnit; // 计费单位：second=元/秒, token=元/M token；null 默认 second

    @Column(name = "params")
    private String params;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "api_key")
    private String apiKey;

    @Column(name = "status")
    private String status;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @Column(name = "deleted")
    private Boolean deleted;
}
