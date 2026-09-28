// 文件说明：BillingRecordEntity：负责网关业务中的持久化实体。
package org.unreal.modelrouter.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_billing_record", indexes = {
    @Index(name = "idx_billing_user", columnList = "user_id"),
    @Index(name = "idx_billing_account", columnList = "user_account"),
    @Index(name = "idx_billing_model", columnList = "model_name"),
    @Index(name = "idx_billing_model_id", columnList = "model_id"),
    @Index(name = "idx_billing_channel", columnList = "channel_id"),
    @Index(name = "idx_billing_date", columnList = "usage_date"),
    @Index(name = "idx_billing_started_at", columnList = "started_at"),
    @Index(name = "idx_billing_total_cost", columnList = "total_cost"),
    @Index(name = "idx_billing_api_key", columnList = "api_key_id")
})
public class BillingRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ========== 用户维度 ==========
    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "user_account")
    private String userAccount;

    @Column(name = "api_key_id")
    private String apiKeyId;

    @Column(name = "api_key_name")
    private String apiKeyName;

    @Column(name = "user_type")
    private Integer userType;

    // ========== 模型维度 ==========
    @Column(name = "model_name", nullable = false)
    private String modelName;

    /** 模型主键（算力平台 ai_model.id 快照，落账时取自定价缓存；定价未命中或历史数据为 NULL，可退化为 model_name 关联） */
    @Column(name = "model_id")
    private Long modelId;

    @Column(name = "service_type", nullable = false, length = 50)
    private String serviceType;

    @Column(name = "channel_id")
    private String channelId;

    @Column(name = "channel_name")
    private String channelName;

    @Column(name = "provider", length = 100)
    private String provider;

    // ========== Token 用量 ==========
    @Column(name = "prompt_tokens")
    private Long promptTokens;

    @Column(name = "completion_tokens")
    private Long completionTokens;

    @Column(name = "total_tokens")
    private Long totalTokens;

    // ========== 6 维 Token 用量（归一化后，新列可空向后兼容） ==========
    @Column(name = "normal_input_tokens")
    private Long normalInputTokens;

    @Column(name = "cache_hit_tokens")
    private Long cacheHitTokens;

    @Column(name = "cache_create_explicit_tokens")
    private Long cacheCreateExplicitTokens;

    @Column(name = "cache_hit_explicit_tokens")
    private Long cacheHitExplicitTokens;

    @Column(name = "normal_output_tokens")
    private Long normalOutputTokens;

    @Column(name = "thinking_tokens")
    private Long thinkingTokens;

    // ========== 价格快照 ==========
    @Column(name = "input_unit_price", precision = 20, scale = 10)
    private BigDecimal inputUnitPrice;

    @Column(name = "output_unit_price", precision = 20, scale = 10)
    private BigDecimal outputUnitPrice;

    @Column(name = "cache_hit_input_unit_price", precision = 20, scale = 10)
    private BigDecimal cacheHitInputUnitPrice;

    @Column(name = "cache_create_input_unit_price", precision = 20, scale = 10)
    private BigDecimal cacheCreateInputUnitPrice;

    @Column(name = "cache_hit_explicit_input_unit_price", precision = 20, scale = 10)
    private BigDecimal cacheHitExplicitInputUnitPrice;

    @Column(name = "thinking_unit_price", precision = 20, scale = 10)
    private BigDecimal thinkingUnitPrice;

    // ========== 用量明细快照（JSONB） ==========
    /**
     * 视频模型账单实际消耗明细（JSONB，仅视频模型账单有值）。
     * 结构：{"billingUnit":"second|token","items":[{"resolution","hasVideoInput","seconds",
     * "tokens","cost","discountAmount","amount"}]}；
     * cost/discountAmount/amount 与 original_cost/discount_amount/total_cost 一致。
     * 对应建表变更：V4__video_billing_metadata.sql。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "usage_detail", columnDefinition = "JSONB")
    private String usageDetail;

    // ========== 模型返回响应快照 ==========
    /**
     * 模型返回响应快照（TEXT，JSON 结构；截断至 8KB、base64 素材已脱敏）。
     * 结构：{"protocol","success","httpStatus","errorCode","errorMessage",
     * "usage","body","bodyTruncated","capturedAt"}；
     * 两条计费链路（透传/视频）统一按 billing_record_id 单行追溯上游返回；
     * 构建失败与旧数据为 NULL。对应建表变更：V5__ai_billing_record_model_id_and_response_snapshot.sql。
     */
    @Column(name = "response_snapshot", columnDefinition = "TEXT")
    private String responseSnapshot;

    // ========== 计费元数据快照 ==========
    @Column(name = "billing_mode")
    private Integer billingMode;

    @Column(name = "thinking_billing_mode")
    private Integer thinkingBillingMode;

    @Column(name = "vendor", length = 50)
    private String vendor;

    // ========== 分项费用（Java侧已按向上取整保留2位小数） ==========
    @Column(name = "input_cost", precision = 20, scale = 6)
    private BigDecimal inputCost;

    @Column(name = "cache_hit_cost", precision = 20, scale = 6)
    private BigDecimal cacheHitCost;

    @Column(name = "cache_create_cost", precision = 20, scale = 6)
    private BigDecimal cacheCreateCost;

    @Column(name = "cache_hit_explicit_cost", precision = 20, scale = 6)
    private BigDecimal cacheHitExplicitCost;

    @Column(name = "output_cost", precision = 20, scale = 6)
    private BigDecimal outputCost;

    @Column(name = "thinking_cost", precision = 20, scale = 6)
    private BigDecimal thinkingCost;

    // ========== 分项账单（折扣后，向上取整保留2位小数，用于导出） ==========
    @Column(name = "input_bill", precision = 20, scale = 2)
    private BigDecimal inputBill;

    @Column(name = "cache_hit_bill", precision = 20, scale = 2)
    private BigDecimal cacheHitBill;

    @Column(name = "cache_create_bill", precision = 20, scale = 2)
    private BigDecimal cacheCreateBill;

    @Column(name = "cache_hit_explicit_bill", precision = 20, scale = 2)
    private BigDecimal cacheHitExplicitBill;

    @Column(name = "output_bill", precision = 20, scale = 2)
    private BigDecimal outputBill;

    @Column(name = "thinking_bill", precision = 20, scale = 2)
    private BigDecimal thinkingBill;

    // ========== 折扣 ==========
    /**
     * 折扣率，小数格式：1.00 = 无折扣，0.80 = 8折。
     * <p>
     * 与算力平台 ai_model.discount（百分比整数，如 80 = 8折）的区别：
     * PlatformDataSyncService 负责将百分比转换为小数后存入此处。
     * 数据库列 DECIMAL(5,2) 可精确存储 0.00 ~ 999.99 范围内的小数。
     */
    @Column(name = "discount_rate", precision = 5, scale = 2)
    private BigDecimal discountRate;

    /** 用户×模型折扣率，小数格式：1.00 = 无折扣，0.90 = 9折。 */
    @Column(name = "user_discount_rate", precision = 10, scale = 6)
    private BigDecimal userDiscountRate;

    /** 企业补贴折扣率，小数格式：1.00 = 无折扣，0.80 = 8折。 */
    @Column(name = "enterprise_discount_rate", precision = 10, scale = 6)
    private BigDecimal enterpriseDiscountRate;

    /** 最终折扣率 = 模型折扣 × 用户折扣 × 企业补贴折扣，小数格式。 */
    @Column(name = "final_discount_rate", precision = 10, scale = 6)
    private BigDecimal finalDiscountRate;

    // ========== 费用（Java侧已按向上取整保留2位小数） ==========
    @Column(name = "original_cost", precision = 20, scale = 6)
    private BigDecimal originalCost;

    @Column(name = "discount_amount", precision = 20, scale = 6)
    private BigDecimal discountAmount;

    @Column(name = "total_cost", precision = 20, scale = 6)
    private BigDecimal totalCost;

    @Column(name = "is_free_quota", nullable = false)
    private Boolean isFreeQuota = false;

    @Column(name = "free_quota_consumed", nullable = false)
    private Long freeQuotaConsumed = 0L;

    // ========== 请求状态 ==========
    @Column(name = "is_success")
    private Boolean isSuccess;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "response_time_ms")
    private Long responseTimeMs;

    @Column(name = "trace_id", length = 100)
    private String traceId;

    @Column(name = "client_ip", length = 50)
    private String clientIp;

    // ========== 时间 ==========
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    // ========== 审计字段 ==========
    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "is_deleted")
    private Boolean isDeleted;

    // ========== 时间维度 ==========
    @Column(name = "usage_date", length = 10)
    private String usageDate;

    @Column(name = "hour_num")
    private Integer hourNum;

    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    @Column(name = "month_num")
    private Integer monthNum;

    @Column(name = "year_num")
    private Integer yearNum;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (isDeleted == null) {
            isDeleted = false;
        }
        if (startedAt != null && usageDate == null) {
            populateTimeDimensions(startedAt);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    private void populateTimeDimensions(LocalDateTime time) {
        usageDate = time.toLocalDate().toString();
        hourNum = time.getHour();
        dayOfWeek = time.getDayOfWeek().getValue() % 7;
        monthNum = time.getMonthValue();
        yearNum = time.getYear();
    }
}
