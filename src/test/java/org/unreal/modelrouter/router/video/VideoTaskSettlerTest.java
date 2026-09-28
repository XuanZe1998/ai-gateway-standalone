package org.unreal.modelrouter.router.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.billing.BillingService;
import org.unreal.modelrouter.billing.ModelPricingService;
import org.unreal.modelrouter.billing.ResponseSnapshotBuilder;
import org.unreal.modelrouter.persistence.jpa.entity.VideoTaskEntity;
import org.unreal.modelrouter.persistence.jpa.repository.VideoTaskRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link VideoTaskSettler} 单元测试（终态落库 + succeeded 幂等计费）。
 */
@ExtendWith(MockitoExtension.class)
class VideoTaskSettlerTest {

    private static final String MODEL = "doubao-seedance-2-5-251215";
    private static final String TASK_NO = "vidtask_test123";
    private static final String SNAPSHOT = "{\"id\":\"" + TASK_NO + "\",\"status\":\"succeeded\"}";

    @Mock
    private VideoTaskRepository repository;
    @Mock
    private BillingService billingService;
    @Mock
    private ModelPricingService pricingService;
    @Mock
    private ResponseSnapshotBuilder responseSnapshotBuilder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private VideoTaskSettler settler;

    @BeforeEach
    void setUp() {
        settler = new VideoTaskSettler(repository, billingService, pricingService, responseSnapshotBuilder);
    }

    // ==================== succeeded：抢占 + 计费 + 回写 ====================

    @Test
    void settle_succeeded_claimsBillsAndAttaches() throws Exception {
        when(repository.claimTerminal(eq(1L), eq("succeeded"), isNull(), isNull(),
                anyString(), any(LocalDateTime.class))).thenReturn(1);
        when(billingService.recordBillingSync(any())).thenReturn(99L);

        settler.settle(entity(), upstream(
                        "{\"status\":\"succeeded\",\"resolution\":\"720p\",\"duration\":5,"
                                + "\"framespersecond\":24,"
                                + "\"usage\":{\"completion_tokens\":1200,\"total_tokens\":1200}}"),
                SNAPSHOT, identity(), "1.2.3.4", "trace-1");

        ArgumentCaptor<BillingService.BillingContext> captor =
                ArgumentCaptor.forClass(BillingService.BillingContext.class);
        verify(billingService).recordBillingSync(captor.capture());
        BillingService.BillingContext ctx = captor.getValue();
        // 视频计费口径：输入 token 恒为 0，completion_tokens 为对账依据
        assertThat(ctx.getServiceType()).isEqualTo("vidGen");
        assertThat(ctx.getPromptTokens()).isZero();
        assertThat(ctx.getCompletionTokens()).isEqualTo(1200L);
        assertThat(ctx.getTotalTokens()).isEqualTo(1200L);
        assertThat(ctx.getModelName()).isEqualTo(MODEL);
        assertThat(ctx.getUserId()).isEqualTo("u1");
        assertThat(ctx.getStartedAt()).isNotNull();
        // 视频实际用量：分辨率大写归一化、时长按 duration 取秒、有无视频输入透传、token 留痕
        BillingService.VideoUsage vu = ctx.getVideoUsage();
        assertThat(vu).isNotNull();
        assertThat(vu.resolution()).isEqualTo("720P");
        assertThat(vu.seconds()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(vu.hasVideoInput()).isFalse();
        assertThat(vu.tokens()).isEqualTo(1200L);
        // 计费记录主键回写对账关联
        verify(repository).attachBillingRecord(1L, 99L);
    }

    @Test
    void settle_succeeded_framesConvertedToFractionalSeconds() {
        // duration 与 frames 二选一：指定 frames 时响应只回传 frames，按帧率折算小数秒
        when(repository.claimTerminal(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);
        when(billingService.recordBillingSync(any())).thenReturn(99L);

        settler.settle(entity(), upstream(
                        "{\"status\":\"succeeded\",\"resolution\":\"720p\","
                                + "\"frames\":121,\"framespersecond\":24,"
                                + "\"usage\":{\"completion_tokens\":900}}"),
                SNAPSHOT, identity(), null, null);

        ArgumentCaptor<BillingService.BillingContext> captor =
                ArgumentCaptor.forClass(BillingService.BillingContext.class);
        verify(billingService).recordBillingSync(captor.capture());
        // 121 ÷ 24 = 5.041667
        assertThat(captor.getValue().getVideoUsage().seconds())
                .isEqualByComparingTo(new BigDecimal("5.041667"));
    }

    @Test
    void settle_succeeded_framesWithoutFps_defaultsTo24() {
        // 帧率字段缺失时默认 24（与方舟协议一致）
        when(repository.claimTerminal(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);
        when(billingService.recordBillingSync(any())).thenReturn(99L);

        settler.settle(entity(), upstream(
                        "{\"status\":\"succeeded\",\"resolution\":\"4k\",\"frames\":120,"
                                + "\"usage\":{\"completion_tokens\":900}}"),
                SNAPSHOT, identity(), null, null);

        ArgumentCaptor<BillingService.BillingContext> captor =
                ArgumentCaptor.forClass(BillingService.BillingContext.class);
        verify(billingService).recordBillingSync(captor.capture());
        BillingService.VideoUsage vu = captor.getValue().getVideoUsage();
        // 分辨率 4k 归一化为 4K；120 ÷ 24 = 5
        assertThat(vu.resolution()).isEqualTo("4K");
        assertThat(vu.seconds()).isEqualByComparingTo(new BigDecimal("5.000000"));
    }

    @Test
    void settle_succeeded_resolutionFallsBackToUsageSr() {
        // 顶层 resolution 缺失时回退 usage.SR
        when(repository.claimTerminal(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);
        when(billingService.recordBillingSync(any())).thenReturn(99L);

        settler.settle(entity(), upstream(
                        "{\"status\":\"succeeded\",\"duration\":10,"
                                + "\"usage\":{\"completion_tokens\":900,\"SR\":\"1080p\"}}"),
                SNAPSHOT, identity(), null, null);

        ArgumentCaptor<BillingService.BillingContext> captor =
                ArgumentCaptor.forClass(BillingService.BillingContext.class);
        verify(billingService).recordBillingSync(captor.capture());
        assertThat(captor.getValue().getVideoUsage().resolution()).isEqualTo("1080P");
    }

    @Test
    void settle_succeeded_alreadyClaimed_skipsBilling() {
        // 并发轮询下终态抢占失败（返回 0）：幂等跳过，绝不重复计费
        when(repository.claimTerminal(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(0);

        settler.settle(entity(), upstream("{\"status\":\"succeeded\"}"),
                SNAPSHOT, identity(), null, null);

        verifyNoInteractions(billingService);
        verify(repository, never()).attachBillingRecord(any(), any());
    }

    @Test
    void settle_succeeded_billingFails_keepsSucceededWithoutBillingId() {
        // 资损兜底：计费失败不抛异常、不回滚终态，billing_record_id 留 NULL 待人工对账
        when(repository.claimTerminal(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);
        when(billingService.recordBillingSync(any())).thenReturn(null);

        assertThatCode(() -> settler.settle(entity(), upstream(
                        "{\"status\":\"succeeded\",\"usage\":{\"completion_tokens\":10}}"),
                SNAPSHOT, identity(), null, null)).doesNotThrowAnyException();

        verify(repository, never()).attachBillingRecord(any(), any());
    }

    @Test
    void settle_succeeded_missingPricing_warnsButStillBills() {
        // 定价预检未命中仅 WARN 告警（漏计费防线），不阻断计费主链路
        when(repository.claimTerminal(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);
        when(pricingService.getPrice(MODEL, "ch-1")).thenReturn(null);
        when(billingService.recordBillingSync(any())).thenReturn(1L);

        settler.settle(entity(), upstream(
                        "{\"status\":\"succeeded\",\"usage\":{\"completion_tokens\":10}}"),
                SNAPSHOT, identity(), null, null);

        verify(pricingService).getPrice(MODEL, "ch-1");
        verify(billingService).recordBillingSync(any());
        verify(repository).attachBillingRecord(1L, 1L);
    }

    @Test
    void settle_succeeded_passesSnapshotToBillingContext() {
        // 计费上下文携带响应快照（builder 包装 usage 原始结构 + 改写后响应体），落账单供对账追溯；
        // 快照体为改写后的对外响应（id=网关任务号），上游原始 task_id 不进入快照
        when(repository.claimTerminal(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);
        when(billingService.recordBillingSync(any())).thenReturn(99L);
        String built = "{\"protocol\":\"vidGen\",\"success\":true}";
        when(responseSnapshotBuilder.build(eq("vidGen"), eq(true), eq(200), isNull(), isNull(),
                any(JsonNode.class), eq(SNAPSHOT))).thenReturn(built);

        settler.settle(entity(), upstream(
                        "{\"status\":\"succeeded\",\"resolution\":\"720p\",\"duration\":5,"
                                + "\"usage\":{\"completion_tokens\":1200}}"),
                SNAPSHOT, identity(), null, null);

        ArgumentCaptor<BillingService.BillingContext> captor =
                ArgumentCaptor.forClass(BillingService.BillingContext.class);
        verify(billingService).recordBillingSync(captor.capture());
        assertThat(captor.getValue().getResponseSnapshot()).isEqualTo(built);
        // 快照入参：usage 原始结构 + 改写后快照体
        verify(responseSnapshotBuilder).build(eq("vidGen"), eq(true), eq(200), isNull(), isNull(),
                any(JsonNode.class), eq(SNAPSHOT));
    }

    @Test
    void settle_succeeded_passesBillingRuleSnapshotToContext() {
        // 创建时快照的计费规则透传到计费上下文（V7：结算优先用提交时刻锁价的规则，不依赖实时定价）
        when(repository.claimTerminal(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);
        when(billingService.recordBillingSync(any())).thenReturn(88L);
        String snapshot = "{\"priceMode\":2,\"billingUnit\":\"second\",\"modelId\":37," +
                "\"rules\":[{\"outputResolution\":\"480P\",\"hasVideoInput\":false,\"price\":10.0}]}";
        when(pricingService.parseVideoPricingSnapshot(snapshot))
                .thenReturn(new ModelPricingService.VideoPricingSnapshot(2, "second", 37L,
                        List.of(new ModelPricingService.ModelPricing.VideoPriceRule("480P", false, new BigDecimal("10.0")))));
        VideoTaskEntity withSnapshot = entity();
        withSnapshot.setBillingRuleSnapshot(snapshot);

        settler.settle(withSnapshot, upstream(
                        "{\"status\":\"succeeded\",\"resolution\":\"480p\",\"duration\":5,"
                                + "\"usage\":{\"completion_tokens\":1200}}"),
                SNAPSHOT, identity(), null, null);

        ArgumentCaptor<BillingService.BillingContext> captor =
                ArgumentCaptor.forClass(BillingService.BillingContext.class);
        verify(billingService).recordBillingSync(captor.capture());
        BillingService.BillingContext ctx = captor.getValue();
        assertThat(ctx.getVideoPricingSnapshot()).isNotNull();
        assertThat(ctx.getVideoPricingSnapshot().priceMode()).isEqualTo(2);
        assertThat(ctx.getVideoPricingSnapshot().billingUnit()).isEqualTo("second");
        assertThat(ctx.getVideoPricingSnapshot().rules()).hasSize(1);
        assertThat(ctx.getVideoPricingSnapshot().rules().get(0).outputResolution()).isEqualTo("480P");
    }

    // ==================== 失败终态 / 中间态 ====================

    @Test
    void settle_failed_writesErrorInfoAndSnapshot() throws Exception {
        when(repository.claimTerminal(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);

        settler.settle(entity(), upstream(
                        "{\"status\":\"failed\",\"error\":{\"code\":\"SensitiveContent\",\"message\":\"内容不合规\"}}"),
                SNAPSHOT, identity(), null, null);

        verify(repository).claimTerminal(eq(1L), eq("failed"), eq("SensitiveContent"),
                eq("内容不合规"), eq(SNAPSHOT), any(LocalDateTime.class));
        verifyNoInteractions(billingService);
    }

    @Test
    void settle_running_updatesStatusOnly() {
        settler.settle(entity(), upstream("{\"status\":\"running\"}"),
                SNAPSHOT, identity(), null, null);

        verify(repository).updateRunningStatus(1L, "running");
        verify(repository, never()).claimTerminal(any(), anyString(), any(), any(), any(), any());
        verifyNoInteractions(billingService);
    }

    @Test
    void markExpired_claimsExpiredStatus() {
        settler.markExpired(entity(), "上游任务记录不存在或已过期");

        verify(repository).claimTerminal(eq(1L), eq("expired"), eq("404"),
                eq("上游任务记录不存在或已过期"), isNull(), any(LocalDateTime.class));
    }

    // ==================== 后台轮询场景（identity=null，从留档重建） ====================

    @Test
    void settle_succeeded_nullIdentity_rebuildsFromEntity() throws Exception {
        // 后台轮询场景：identity 为 null，计费身份从留档字段重建
        when(repository.claimTerminal(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);
        when(billingService.recordBillingSync(any())).thenReturn(88L);

        settler.settle(entity(), upstream(
                        "{\"status\":\"succeeded\",\"resolution\":\"720p\",\"duration\":5,"
                                + "\"usage\":{\"completion_tokens\":1200}}"),
                null, null, null, null);

        ArgumentCaptor<BillingService.BillingContext> captor =
                ArgumentCaptor.forClass(BillingService.BillingContext.class);
        verify(billingService).recordBillingSync(captor.capture());
        BillingService.BillingContext ctx = captor.getValue();
        // 身份从留档重建：userId/userAccount/apiKeyId/enterpriseId/userType 完整
        assertThat(ctx.getUserId()).isEqualTo("u1");
        assertThat(ctx.getUserAccount()).isEqualTo("u1-acct");
        assertThat(ctx.getApiKeyId()).isEqualTo("key-1");
        assertThat(ctx.getApiKeyName()).isEqualTo("key-name");
        assertThat(ctx.getEnterpriseId()).isEqualTo(1L);
        assertThat(ctx.getCompanyId()).isEqualTo("C001");
        assertThat(ctx.getUserType()).isEqualTo(1);
        assertThat(ctx.getAccountId()).isEqualTo("C001"); // 企业用户按 companyId
        assertThat(ctx.getAccountType()).isEqualTo(1);
        // enterpriseName 留档未存，为 null（余额扣减链路实时查询）
        assertThat(ctx.getEnterpriseName()).isNull();
        // clientIp/traceId 为 null（后台轮询无请求上下文）
        assertThat(ctx.getClientIp()).isNull();
        assertThat(ctx.getTraceId()).isNull();
        // 计费记录主键正常回写
        verify(repository).attachBillingRecord(1L, 88L);
    }

    @Test
    void settle_succeeded_nullIdentity_nullUserId_fallsBackToSystem() throws Exception {
        // 留档 userId 缺失（极端脏数据）：回退 SYSTEM 身份，计费链路仍能走通（不 NPE）
        when(repository.claimTerminal(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);
        when(billingService.recordBillingSync(any())).thenReturn(77L);

        VideoTaskEntity dirtyEntity = entity();
        dirtyEntity.setUserId(null);

        settler.settle(dirtyEntity, upstream(
                        "{\"status\":\"succeeded\",\"resolution\":\"720p\",\"duration\":5,"
                                + "\"usage\":{\"completion_tokens\":1200}}"),
                null, null, null, null);

        ArgumentCaptor<BillingService.BillingContext> captor =
                ArgumentCaptor.forClass(BillingService.BillingContext.class);
        verify(billingService).recordBillingSync(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("system");
    }

    @Test
    void settle_succeeded_nullSnapshot_billingSnapshotStillBuilt() throws Exception {
        // 后台轮询场景：snapshot 为 null（无对外响应），计费快照仅含 usage 原始结构
        when(repository.claimTerminal(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);
        when(billingService.recordBillingSync(any())).thenReturn(66L);
        String built = "{\"protocol\":\"vidGen\",\"success\":true}";
        when(responseSnapshotBuilder.build(eq("vidGen"), eq(true), eq(200), isNull(), isNull(),
                any(JsonNode.class), isNull())).thenReturn(built);

        settler.settle(entity(), upstream(
                        "{\"status\":\"succeeded\",\"resolution\":\"720p\",\"duration\":5,"
                                + "\"usage\":{\"completion_tokens\":1200}}"),
                null, identity(), null, null);

        ArgumentCaptor<BillingService.BillingContext> captor =
                ArgumentCaptor.forClass(BillingService.BillingContext.class);
        verify(billingService).recordBillingSync(captor.capture());
        assertThat(captor.getValue().getResponseSnapshot()).isEqualTo(built);
        // 快照入参：usage 原始结构 + snapshot 为 null
        verify(responseSnapshotBuilder).build(eq("vidGen"), eq(true), eq(200), isNull(), isNull(),
                any(JsonNode.class), isNull());
    }

    // ==================== platformUser 留档恢复（后台轮询企业折扣） ====================

    @Test
    void settle_succeeded_nullIdentity_platformUserTrue_restoredFromEntity() {
        // 平台企业用户任务：创建时留档 platform_user=true，轮询重建身份必须恢复原值（否则丢企业折扣）
        when(repository.claimTerminal(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);
        when(billingService.recordBillingSync(any())).thenReturn(55L);

        VideoTaskEntity platformEntity = entity();
        platformEntity.setPlatformUser(Boolean.TRUE);

        settler.settle(platformEntity, upstream(
                        "{\"status\":\"succeeded\",\"resolution\":\"720p\",\"duration\":5,"
                                + "\"usage\":{\"completion_tokens\":1200}}"),
                null, null, null, null);

        ArgumentCaptor<BillingService.BillingContext> captor =
                ArgumentCaptor.forClass(BillingService.BillingContext.class);
        verify(billingService).recordBillingSync(captor.capture());
        assertThat(captor.getValue().getPlatformUser()).isTrue();
    }

    @Test
    void settle_succeeded_nullIdentity_platformUserNull_defaultsToFalse() {
        // 历史数据/本地 Key 用户：platform_user 为 NULL（或 false），重建身份按 false 兜底
        when(repository.claimTerminal(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);
        when(billingService.recordBillingSync(any())).thenReturn(44L);

        // entity() 默认 platformUser 为 null（历史数据场景）
        settler.settle(entity(), upstream(
                        "{\"status\":\"succeeded\",\"resolution\":\"720p\",\"duration\":5,"
                                + "\"usage\":{\"completion_tokens\":1200}}"),
                null, null, null, null);

        ArgumentCaptor<BillingService.BillingContext> captor =
                ArgumentCaptor.forClass(BillingService.BillingContext.class);
        verify(billingService).recordBillingSync(captor.capture());
        assertThat(captor.getValue().getPlatformUser()).isFalse();
    }

    // ==================== responseTimeMs：上游真实生成耗时 ====================

    @Test
    void settle_succeeded_upstreamTimestampsPresent_setsResponseTimeMs() {
        // 上游返回 created_at/updated_at 时，responseTimeMs = (updated_at - created_at) * 1000
        when(repository.claimTerminal(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);
        when(billingService.recordBillingSync(any())).thenReturn(99L);

        settler.settle(entity(), upstream(
                        "{\"status\":\"succeeded\",\"created_at\":1787639654,\"updated_at\":1787639795,"
                                + "\"resolution\":\"720p\",\"duration\":5,"
                                + "\"usage\":{\"completion_tokens\":1200}}"),
                SNAPSHOT, identity(), null, null);

        ArgumentCaptor<BillingService.BillingContext> captor =
                ArgumentCaptor.forClass(BillingService.BillingContext.class);
        verify(billingService).recordBillingSync(captor.capture());
        // 141 秒 = 141000 ms
        assertThat(captor.getValue().getResponseTimeMs()).isEqualTo(141000L);
    }

    @Test
    void settle_succeeded_upstreamTimestampsMissing_responseTimeMsIsNull() {
        // 上游未返回时间戳时 responseTimeMs 为 null（不阻断计费）
        when(repository.claimTerminal(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);
        when(billingService.recordBillingSync(any())).thenReturn(99L);

        settler.settle(entity(), upstream(
                        "{\"status\":\"succeeded\",\"resolution\":\"720p\",\"duration\":5,"
                                + "\"usage\":{\"completion_tokens\":1200}}"),
                SNAPSHOT, identity(), null, null);

        ArgumentCaptor<BillingService.BillingContext> captor =
                ArgumentCaptor.forClass(BillingService.BillingContext.class);
        verify(billingService).recordBillingSync(captor.capture());
        assertThat(captor.getValue().getResponseTimeMs()).isNull();
    }

    @Test
    void settle_succeeded_upstreamTimestampsInvalid_responseTimeMsIsNull() {
        // updated_at <= created_at（上游脏数据）时 responseTimeMs 为 null
        when(repository.claimTerminal(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);
        when(billingService.recordBillingSync(any())).thenReturn(99L);

        settler.settle(entity(), upstream(
                        "{\"status\":\"succeeded\",\"created_at\":1787639795,\"updated_at\":1787639654,"
                                + "\"resolution\":\"720p\",\"duration\":5,"
                                + "\"usage\":{\"completion_tokens\":1200}}"),
                SNAPSHOT, identity(), null, null);

        ArgumentCaptor<BillingService.BillingContext> captor =
                ArgumentCaptor.forClass(BillingService.BillingContext.class);
        verify(billingService).recordBillingSync(captor.capture());
        assertThat(captor.getValue().getResponseTimeMs()).isNull();
    }

    // ==================== 辅助 ====================

    private static VideoTaskEntity entity() {
        return VideoTaskEntity.builder()
                .id(1L)
                .taskNo(TASK_NO)
                .upstreamTaskId("ark-task-123")
                .status("running")
                .modelName(MODEL)
                .vendor("volcengine")
                .channelId("ch-1")
                .channelName("ark-channel")
                .userId("u1")
                .userAccount("u1-acct")
                .apiKeyId("key-1")
                .apiKeyName("key-name")
                .enterpriseId(1L)
                .companyId("C001")
                .userType(1)
                .hasVideoInput(false)
                .submittedAt(LocalDateTime.now().minusMinutes(2))
                .build();
    }

    private static UserIdentity identity() {
        return new UserIdentity("u1", "u1-acct", "key-1", "key-name",
                1L, "ent", "C001", false, 1, 7L, null);
    }

    private JsonNode upstream(final String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
