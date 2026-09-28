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

import java.time.LocalDateTime;

/**
 * 视频生成任务留档表 ai_video_task。
 *
 * 网关作为中转方对异步视频任务的追溯核心：记录网关任务号（对外暴露）与上游任务 ID
 * （不对外暴露）的映射、路由维度（渠道/实例/厂商）、请求快照（脱敏后）与状态流转，
 * 供内部对账、客诉追溯及第二阶段查询计费使用。
 *
 * 对应建表脚本：resources/db/scripts/V3__create_ai_video_task.sql（手工执行，ddl-auto: none）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_video_task", indexes = {
    @Index(name = "uk_video_task_no", columnList = "task_no", unique = true),
    @Index(name = "idx_video_task_upstream", columnList = "upstream_task_id"),
    @Index(name = "idx_video_task_user", columnList = "user_id"),
    @Index(name = "idx_video_task_api_key", columnList = "api_key_id"),
    @Index(name = "idx_video_task_model", columnList = "model_name"),
    @Index(name = "idx_video_task_submitted", columnList = "submitted_at")
})
public class VideoTaskEntity {

    /** 主键 ID，数据库自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ========== 任务标识 ==========

    /** 网关任务号（对外暴露的任务 ID），格式 vidtask_&lt;uuid&gt;，唯一索引 */
    @Column(name = "task_no", nullable = false, length = 64)
    private String taskNo;

    /** 上游（如火山方舟）任务 ID，仅内部留档，不对外暴露（防渠道信息泄露）；实测方舟 ID 超 128 字符，列宽放宽至 512，严禁截断（截断会导致查询链路用错误 ID 打上游、任务永不结算） */
    @Column(name = "upstream_task_id", length = 512)
    private String upstreamTaskId;

    /**
     * 任务状态：submitted（已提交上游）/ queued / running / succeeded / failed / expired / cancelled。
     * 第一阶段创建时记 submitted（或上游创建响应返回的状态）；状态流转由第二阶段查询链路负责。
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    // ========== 路由维度 ==========

    /** 模型名称（客户端请求的 model 字段，如 doubao-seedance-2-5-*） */
    @Column(name = "model_name", nullable = false)
    private String modelName;

    /** 上游厂商标识（如 volcengine），取自路由实例 */
    @Column(name = "vendor", length = 50)
    private String vendor;

    /** 关联算力平台渠道 ID */
    @Column(name = "channel_id")
    private String channelId;

    /** 渠道/实例名称（留档展示用） */
    @Column(name = "channel_name")
    private String channelName;

    /** 路由实例唯一标识 */
    @Column(name = "instance_id")
    private String instanceId;

    /** 上游服务 baseUrl（截断至 500 字符，追溯上游环境用） */
    @Column(name = "base_url", length = 500)
    private String baseUrl;

    // ========== 关键参数提取（供二期计费/对账） ==========

    /** 视频分辨率：480p / 720p / 1080p / 4k */
    @Column(name = "resolution", length = 20)
    private String resolution;

    /** 视频宽高比：16:9 / 4:3 / 1:1 / 3:4 / 9:16 / 21:9 / adaptive */
    @Column(name = "ratio", length = 20)
    private String ratio;

    /** 视频时长（秒），与 frames 二选一 */
    @Column(name = "duration")
    private Integer duration;

    /** 视频帧数（优先级高于 duration） */
    @Column(name = "frames")
    private Integer frames;

    /** 是否生成有声视频 */
    @Column(name = "generate_audio")
    private Boolean generateAudio;

    /** 是否样片模式（Draft，低成本 480p 预览） */
    @Column(name = "draft")
    private Boolean draft;

    /**
     * 本次调用是否有视频输入（请求 content 含 type=video_url 项）：
     * true=有 / false=无 / null=无法判定（content 缺失或非数组）。
     * 供视频模型按条件定价（price_mode=2）匹配分辨率价格规则使用；
     * 上游响应不包含该信息，只能创建时从请求解析留档。
     * 对应建表变更：V4__video_billing_metadata.sql。
     */
    @Column(name = "has_video_input")
    private Boolean hasVideoInput;

    // ========== 快照 ==========

    /** 请求快照（TEXT，base64 素材已脱敏截断；公网 URL/提示词原样保留可追溯） */
    @Column(name = "request_snapshot", columnDefinition = "TEXT")
    private String requestSnapshot;

    /** 上游创建接口的首次响应快照（TEXT，截断至 8KB 保存） */
    @Column(name = "first_response_snapshot", columnDefinition = "TEXT")
    private String firstResponseSnapshot;

    /**
     * 上游终态响应快照（TEXT，第二阶段查询链路回写）。
     * 内容为改写后的对外响应（id 已替换为网关任务号），
     * 终态后客户端再查询时直出本字段，不再打上游
     * （防上游 7 天记录过期 404、防 succeeded 重复结算）。
     * 对应建表变更：V4__alter_ai_video_task_add_response_snapshot.sql。
     */
    @Column(name = "response_snapshot", columnDefinition = "TEXT")
    private String responseSnapshot;

    /**
     * 视频计费规则快照（JSONB，任务创建时从定价缓存快照）：
     * 提交时刻生效的计费规则（priceMode + billingUnit + modelId + 启用规则行 rules，
     * price 为已转换单价）。结算时优先用快照计价——提交时刻锁价（业界异步任务惯例），
     * 平台侧后续改价/删规则不影响已提交任务的账单生成；
     * 快照缺失（创建时定价未命中/非视频模型）时结算回退实时查询定价，兼容历史数据。
     * 对应建表变更：V7__ai_video_task_billing_rule_snapshot.sql。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "billing_rule_snapshot", columnDefinition = "JSONB")
    private String billingRuleSnapshot;

    // ========== 用户维度 ==========

    /** 调用方用户 ID（UserIdentity.userId） */
    @Column(name = "user_id")
    private String userId;

    /** 调用方用户账号 */
    @Column(name = "user_account")
    private String userAccount;

    /** 调用所用 API Key 的 ID */
    @Column(name = "api_key_id")
    private String apiKeyId;

    /** 调用所用 API Key 的名称 */
    @Column(name = "api_key_name")
    private String apiKeyName;

    /** 用户类型：1 企业用户 / 2 个人用户 */
    @Column(name = "user_type")
    private Integer userType;

    /** 所属企业 ID（企业用户时有值） */
    @Column(name = "enterprise_id")
    private Long enterpriseId;

    /** 所属公司 ID */
    @Column(name = "company_id")
    private String companyId;

    /**
     * 平台用户标记（任务创建时 UserIdentity.platformUser 快照）：
     * true=算力平台 API Key 用户（参与企业折扣/余额校验），false=本地 API Key/JWT/SYSTEM。
     * 后台轮询结算（VideoTaskSettler.rebuildIdentityFromEntity）无请求上下文，
     * 靠本列恢复原值，避免轮询路径丢失企业折扣；
     * 历史数据为 NULL，重建身份时按 false 兜底（维持原行为）。
     * 对应建表变更：V6__ai_video_task_platform_user.sql。
     */
    @Column(name = "platform_user")
    private Boolean platformUser;

    // ========== 追溯 ==========

    /** 请求链路追踪 ID（TracingContext.traceId） */
    @Column(name = "trace_id", length = 100)
    private String traceId;

    /** 客户端 IP */
    @Column(name = "client_ip", length = 50)
    private String clientIp;

    /** 失败时的错误码（上游 HTTP 状态码或网关 504 等） */
    @Column(name = "error_code", length = 100)
    private String errorCode;

    /** 失败时的错误信息（截断至 1000 字符） */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    // ========== 时间 ==========

    /** 提交上游时间 */
    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    /** 任务终态时间（第二阶段查询链路回写） */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    // ========== 计费关联（预留） ==========

    /** 第二阶段任务成功后计费落账的 ai_billing_record.id，用于对账 */
    @Column(name = "billing_record_id")
    private Long billingRecordId;

    // ========== 审计字段 ==========

    /** 创建人（取调用方用户账号） */
    @Column(name = "created_by")
    private String createdBy;

    /** 创建时间（@PrePersist 自动填充） */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 最后更新人 */
    @Column(name = "updated_by")
    private String updatedBy;

    /** 最后更新时间（@PrePersist/@PreUpdate 自动填充） */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 逻辑删除标记：false 有效 / true 已删除（数据库列名沿用 is_deleted 惯例，字段命名遵循 POJO 规范不加 is 前缀） */
    @Column(name = "is_deleted")
    private Boolean deleted;

    /** 持久化前自动填充审计字段与默认值 */
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (deleted == null) {
            deleted = false;
        }
        if (submittedAt == null) {
            submittedAt = LocalDateTime.now();
        }
    }

    /** 更新前自动刷新更新时间 */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
