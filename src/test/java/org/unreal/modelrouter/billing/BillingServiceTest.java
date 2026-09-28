// 文件说明：测试 BillingServiceTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.billing.usage.TokenUsage;
import org.unreal.modelrouter.persistence.jpa.entity.BillingRecordEntity;
import org.unreal.modelrouter.persistence.jpa.repository.BillingRecordRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformSystemUserRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    @Mock private BillingRecordRepository billingRepository;
    @Mock private ModelPricingService pricingService;
    @Mock private BalanceDeductionService balanceDeductionService;
    @Mock private EnterpriseLookupService enterpriseLookupService;
    @Mock private PlatformSystemUserRepository systemUserRepository;
    @Mock private DiscountCalculationService discountCalculationService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private BillingService billingService;

    @BeforeEach
    void setUp() {
        lenient().when(discountCalculationService.calculate(any(UserIdentity.class), any(), any()))
                .thenReturn(new DiscountBreakdown(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
    }

    @Test
    void shouldRecordFreeQuotaBill_whenHitFreeQuota() {
        BillingService.BillingContext ctx = BillingService.BillingContext.create()
                .userId("u1")
                .modelName("gpt-4")
                .serviceType("chat")
                .promptTokens(40L)
                .completionTokens(60L)
                .totalTokens(100L)
                .enterpriseId(1L)
                .companyId("C001")
                .userType(1)
                .accountId("C001")
                .accountType(1)
                .isFreeQuota(true)
                .freeQuotaConsumed(100L);

        billingService.recordBilling(ctx);

        ArgumentCaptor<BillingRecordEntity> captor = ArgumentCaptor.forClass(BillingRecordEntity.class);
        verify(billingRepository).save(captor.capture());
        BillingRecordEntity record = captor.getValue();
        assertThat(record.getIsFreeQuota()).isTrue();
        assertThat(record.getFreeQuotaConsumed()).isEqualTo(100L);
        assertThat(record.getTotalCost()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(balanceDeductionService, never()).deductAndAlert(any(), any(), any(), any());
    }

    @Test
    void shouldDeductBalance_whenNormalBilling() {
        // Given
        ModelPricingService.ModelPricing pricing = new ModelPricingService.ModelPricing(
                "gpt-4", null,
                new BigDecimal("0.001"),
                new BigDecimal("0.002"),
                BigDecimal.ONE
        );

        when(pricingService.getPrice("gpt-4", null)).thenReturn(pricing);

        EnterpriseLookupService.EnterpriseInfo enterpriseInfo = new EnterpriseLookupService.EnterpriseInfo();
        enterpriseInfo.setEnterpriseName("TestCorp");
        when(enterpriseLookupService.lookupByAccount("C001", 1)).thenReturn(Optional.of(enterpriseInfo));
        when(systemUserRepository.findByUserId("u1")).thenReturn(Optional.empty());

        BillingService.BillingContext ctx = BillingService.BillingContext.create()
                .userId("u1")
                .modelName("gpt-4")
                .serviceType("chat")
                .promptTokens(50L)
                .completionTokens(50L)
                .totalTokens(100L)
                .enterpriseId(1L)
                .companyId("C001")
                .userType(1)
                .accountId("C001")
                .accountType(1)
                .isFreeQuota(false);

        // When
        billingService.recordBilling(ctx);

        // Then
        ArgumentCaptor<BillingRecordEntity> captor = ArgumentCaptor.forClass(BillingRecordEntity.class);
        verify(billingRepository).save(captor.capture());
        BillingRecordEntity record = captor.getValue();
        assertThat(record.getIsFreeQuota()).isFalse();
        assertThat(record.getTotalCost()).isGreaterThan(BigDecimal.ZERO);

        verify(balanceDeductionService).deductAndAlert(
                eq("C001"), eq(1), eq(new BigDecimal("0.15")), any(EnterpriseLookupService.EnterpriseInfo.class));
    }

    @Test
    void shouldWriteDiscountSnapshotForEnterpriseUser() {
        // Given
        ModelPricingService.ModelPricing pricing = new ModelPricingService.ModelPricing(
                "gpt-4", null,
                new BigDecimal("0.001"),
                new BigDecimal("0.002"),
                new BigDecimal("0.90")
        );
        when(pricingService.getPrice("gpt-4", null)).thenReturn(pricing);

        DiscountBreakdown discounts = new DiscountBreakdown(
                new BigDecimal("0.900000"),
                new BigDecimal("0.850000"),
                new BigDecimal("0.800000"),
                new BigDecimal("0.612000"));
        when(discountCalculationService.calculate(any(UserIdentity.class), eq("gpt-4"), eq(null)))
                .thenReturn(discounts);

        EnterpriseLookupService.EnterpriseInfo enterpriseInfo = new EnterpriseLookupService.EnterpriseInfo();
        enterpriseInfo.setEnterpriseName("TestCorp");
        when(enterpriseLookupService.lookupByAccount("C001", 1)).thenReturn(Optional.of(enterpriseInfo));
        when(systemUserRepository.findByUserId("u1")).thenReturn(Optional.empty());

        BillingService.BillingContext ctx = BillingService.BillingContext.create()
                .userId("u1")
                .userAccount("u1")
                .modelName("gpt-4")
                .serviceType("chat")
                .promptTokens(1000L)
                .completionTokens(500L)
                .totalTokens(1500L)
                .enterpriseId(10L)
                .enterpriseName("TestEnt")
                .companyId("C001")
                .userType(1)
                .systemUserId(100L)
                .accountId("C001")
                .accountType(1)
                .isFreeQuota(false);

        // When
        billingService.recordBilling(ctx);

        // Then
        ArgumentCaptor<BillingRecordEntity> captor = ArgumentCaptor.forClass(BillingRecordEntity.class);
        verify(billingRepository).save(captor.capture());
        BillingRecordEntity record = captor.getValue();

        assertThat(record.getUserType()).isEqualTo(1);
        assertThat(record.getDiscountRate()).isEqualByComparingTo(new BigDecimal("0.900000"));
        assertThat(record.getUserDiscountRate()).isEqualByComparingTo(new BigDecimal("0.850000"));
        assertThat(record.getEnterpriseDiscountRate()).isEqualByComparingTo(new BigDecimal("0.800000"));
        assertThat(record.getFinalDiscountRate()).isEqualByComparingTo(new BigDecimal("0.612000"));
        // 原价 2.00（1.0+1.0），应付比例 0.612 → 分项账单 0.62+0.62=1.24，折扣额 0.76
        assertThat(record.getDiscountAmount()).isEqualByComparingTo(new BigDecimal("0.760000"));
        assertThat(record.getTotalCost()).isEqualByComparingTo(new BigDecimal("1.240000"));

        verify(balanceDeductionService).deductAndAlert(
                eq("C001"), eq(1), eq(new BigDecimal("1.24")), any(EnterpriseLookupService.EnterpriseInfo.class));
    }

    @Test
    void shouldWriteDiscountSnapshotForPersonalUser() {
        // Given
        ModelPricingService.ModelPricing pricing = new ModelPricingService.ModelPricing(
                "gpt-4", null,
                new BigDecimal("0.001"),
                new BigDecimal("0.002"),
                new BigDecimal("0.90")
        );
        when(pricingService.getPrice("gpt-4", null)).thenReturn(pricing);

        DiscountBreakdown discounts = new DiscountBreakdown(
                new BigDecimal("0.900000"),
                new BigDecimal("0.850000"),
                BigDecimal.ONE,
                new BigDecimal("0.765000"));
        when(discountCalculationService.calculate(any(UserIdentity.class), eq("gpt-4"), eq(null)))
                .thenReturn(discounts);

        EnterpriseLookupService.EnterpriseInfo personalInfo = new EnterpriseLookupService.EnterpriseInfo();
        personalInfo.setEnterpriseName("u1");
        when(enterpriseLookupService.lookupByAccount("u1", 2)).thenReturn(Optional.of(personalInfo));
        when(systemUserRepository.findByUserId("u1")).thenReturn(Optional.empty());

        BillingService.BillingContext ctx = BillingService.BillingContext.create()
                .userId("u1")
                .userAccount("u1")
                .apiKeyId("ak-001")
                .modelName("gpt-4")
                .serviceType("chat")
                .promptTokens(1000L)
                .completionTokens(500L)
                .totalTokens(1500L)
                .enterpriseId(null)
                .companyId(null)
                .userType(2)
                .systemUserId(100L)
                .platformUser(true)
                .accountId("u1")
                .accountType(2)
                .isFreeQuota(false);

        // When
        billingService.recordBilling(ctx);

        // Then
        ArgumentCaptor<BillingRecordEntity> captor = ArgumentCaptor.forClass(BillingRecordEntity.class);
        verify(billingRepository).save(captor.capture());
        BillingRecordEntity record = captor.getValue();

        assertThat(record.getUserType()).isEqualTo(2);
        assertThat(record.getDiscountRate()).isEqualByComparingTo(new BigDecimal("0.900000"));
        assertThat(record.getUserDiscountRate()).isEqualByComparingTo(new BigDecimal("0.850000"));
        assertThat(record.getEnterpriseDiscountRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(record.getFinalDiscountRate()).isEqualByComparingTo(new BigDecimal("0.765000"));
        // 原价 2.00，应付比例 0.765 → 分项账单 0.77+0.77=1.54，折扣额 0.46
        assertThat(record.getDiscountAmount()).isEqualByComparingTo(new BigDecimal("0.460000"));
        assertThat(record.getTotalCost()).isEqualByComparingTo(new BigDecimal("1.540000"));

        // 验证传入 DiscountCalculationService 的 UserIdentity 正确携带 platformUser=true
        verify(discountCalculationService).calculate(
                argThat(id -> id != null && id.platformUser() && Integer.valueOf(2).equals(id.userType())
                        && Long.valueOf(100L).equals(id.systemUserId())),
                eq("gpt-4"), eq(null));

        // 个人用户也走余额扣减
        verify(balanceDeductionService).deductAndAlert(
                eq("u1"), eq(2), eq(new BigDecimal("1.54")), any(EnterpriseLookupService.EnterpriseInfo.class));
    }

    /**
     * 防资损核心场景：模型只启用输入+输出（缓存/思考维度均未启用），
     * 上游返回的 cacheHit/cacheCreateExplicit/cacheHitExplicit/thinking 必须归并到 normalInput/normalOutput，
     * 不能蒸发。对应 kimi-k3 案例：cacheHit=86 归并后 normalInput=86，thinking=135 归并后 normalOutput=162。
     */
    @Test
    void shouldFoldDisabledDimensionsIntoEnabledOnes_whenOnlyInputOutputEnabled() {
        // Given: 只启用输入+输出，缓存/思考维度全部禁用
        ModelPricingService.ModelPricing pricing = new ModelPricingService.ModelPricing(
                "kimi-k3", null, 1L, "moonshot",
                1,                          // billingMode=整体
                new BigDecimal("0.00001"),  // inputPrice
                BigDecimal.ZERO,            // cacheHitInputPrice（未启用，值为0）
                new BigDecimal("0.00002"),  // outputPrice
                BigDecimal.ZERO,            // cacheCreateInputPrice
                BigDecimal.ZERO,            // cacheHitExplicitInputPrice
                true,   // enableInputToken
                false,  // enableCacheHitInput  ← 未启用
                true,   // enableOutputToken
                false,  // enableCacheCreateInput  ← 未启用
                false,  // enableCacheHitExplicitInput  ← 未启用
                1,      // thinkingBillingMode=并入输出
                BigDecimal.ZERO,            // thinkingPrice
                BigDecimal.ONE,             // discountRate
                List.of()
        );
        when(pricingService.getPrice("kimi-k3", null)).thenReturn(pricing);

        EnterpriseLookupService.EnterpriseInfo info = new EnterpriseLookupService.EnterpriseInfo();
        when(enterpriseLookupService.lookupByAccount("C001", 1)).thenReturn(Optional.of(info));
        when(systemUserRepository.findByUserId("u1")).thenReturn(Optional.empty());

        // 上游返回：normalInput=0, cacheHit=86, normalOutput=27, thinking=135
        TokenUsage usage = new TokenUsage(0, 86, 0, 0, 27, 135, 86, 162, 248);
        BillingService.BillingContext ctx = BillingService.BillingContext.create()
                .userId("u1").modelName("kimi-k3").serviceType("chat")
                .tokenUsage(usage)
                .enterpriseId(1L).companyId("C001").userType(1)
                .accountId("C001").accountType(1).isFreeQuota(false);

        // When
        billingService.recordBilling(ctx);

        // Then: cacheHit=86 归并到 normalInput → 86；thinking=135 归并到 normalOutput → 27+135=162
        ArgumentCaptor<BillingRecordEntity> captor = ArgumentCaptor.forClass(BillingRecordEntity.class);
        verify(billingRepository).save(captor.capture());
        BillingRecordEntity record = captor.getValue();

        // 6 维字段记录归并后的实际计费口径
        assertThat(record.getNormalInputTokens()).isEqualTo(86L);   // 0 + 86(cacheHit归并)
        assertThat(record.getCacheHitTokens()).isEqualTo(0L);       // 已归并，置0
        assertThat(record.getCacheCreateExplicitTokens()).isEqualTo(0L);
        assertThat(record.getCacheHitExplicitTokens()).isEqualTo(0L);
        assertThat(record.getNormalOutputTokens()).isEqualTo(162L); // 27 + 135(thinking并入)
        assertThat(record.getThinkingTokens()).isEqualTo(0L);

        // 费用按分向上取整（防资损口径）：86×0.00001=0.00086→0.01，162×0.00002=0.00324→0.01
        assertThat(record.getInputCost()).isEqualByComparingTo(new BigDecimal("0.010000"));
        assertThat(record.getCacheHitCost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(record.getOutputCost()).isEqualByComparingTo(new BigDecimal("0.010000"));
        assertThat(record.getThinkingCost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(record.getOriginalCost()).isEqualByComparingTo(new BigDecimal("0.020000"));
    }

    @Test
    void shouldSkipDeduction_whenAccountIdMissing() {
        // Given
        ModelPricingService.ModelPricing pricing = new ModelPricingService.ModelPricing(
                "gpt-4", null,
                new BigDecimal("0.001"),
                new BigDecimal("0.002"),
                BigDecimal.ONE
        );
        when(pricingService.getPrice("gpt-4", null)).thenReturn(pricing);

        BillingService.BillingContext ctx = BillingService.BillingContext.create()
                .userId("u1")
                .modelName("gpt-4")
                .serviceType("chat")
                .promptTokens(50L)
                .completionTokens(50L)
                .totalTokens(100L)
                .enterpriseId(1L)
                .companyId("C001")
                .userType(1)
                .isFreeQuota(false);

        // When
        billingService.recordBilling(ctx);

        // Then
        verify(enterpriseLookupService, never()).lookupByAccount(any(), any());
        verify(balanceDeductionService, never()).deductAndAlert(any(), any(), any(), any());
    }

    // ==================== 视频模型计费（价格模式 + 计费单位 + 分辨率规则） ====================

    /** 视频定价辅助构造：token 六维价全置 ZERO，视频元数据为给定值 */
    private ModelPricingService.ModelPricing videoPricing(int priceMode, String billingUnit,
                                                          List<ModelPricingService.ModelPricing.VideoPriceRule> rules) {
        return new ModelPricingService.ModelPricing(
                "doubao-seedance-2-0", null, 1L, "volcengine",
                1, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                true, true, true, false, false,
                1, BigDecimal.ZERO, BigDecimal.ONE, List.of(),
                priceMode, billingUnit, rules);
    }

    private BillingService.BillingContext videoCtx(String resolution, Boolean hasVideoInput,
                                                   BigDecimal seconds, Long tokens) {
        return BillingService.BillingContext.create()
                .userId("u1")
                .modelName("doubao-seedance-2-0")
                .serviceType("vidGen")
                .vendor("volcengine")
                .videoUsage(new BillingService.VideoUsage(resolution, hasVideoInput, seconds, tokens))
                .isFreeQuota(false);
    }

    @Test
    void shouldBillVideoBySnapshotRules_whenRealtimePricingMissing() {
        // 快照优先（V7）：结算用任务创建时锁定的规则，即使平台侧已删规则/实时定价未命中也能计价——
        // 本次事故场景（480P 规则行被删导致拒计费）在快照落地后不再发生
        when(pricingService.getPrice("doubao-seedance-2-0", null)).thenReturn(null);

        BillingService.BillingContext ctx = videoCtx("480P", false, new BigDecimal("5"), 60682L)
                .videoPricingSnapshot(new ModelPricingService.VideoPricingSnapshot(2, "second", 37L,
                        List.of(new ModelPricingService.ModelPricing.VideoPriceRule("480P", false, new BigDecimal("10.0")))));

        billingService.recordBillingSync(ctx);

        ArgumentCaptor<BillingRecordEntity> captor = ArgumentCaptor.forClass(BillingRecordEntity.class);
        verify(billingRepository).save(captor.capture());
        BillingRecordEntity record = captor.getValue();
        // 5 秒 × 10 元/秒 = 50；modelId 取快照中的模型主键
        assertThat(record.getTotalCost()).isEqualByComparingTo(new BigDecimal("50.000000"));
        assertThat(record.getModelId()).isEqualTo(37L);
        // usage_detail 记录快照口径计量
        assertThat(record.getUsageDetail()).contains("\"seconds\":5").contains("\"480P\"");
    }

    @Test
    void shouldRejectBilling_whenSnapshotRuleNotMatched() {
        // 快照存在但请求分辨率无匹配规则行（提交时刻即无该分辨率价格）：拒计费留人工对账，口径不变
        when(pricingService.getPrice("doubao-seedance-2-0", null)).thenReturn(null);

        BillingService.BillingContext ctx = videoCtx("480P", false, new BigDecimal("5"), 100L)
                .videoPricingSnapshot(new ModelPricingService.VideoPricingSnapshot(2, "second", 37L,
                        List.of(new ModelPricingService.ModelPricing.VideoPriceRule("720P", false, new BigDecimal("50.0")))));

        Long id = billingService.recordBillingSync(ctx);

        assertThat(id).isNull();
        verify(billingRepository, never()).save(any());
    }

    @Test
    void shouldBillVideoByResolution_whenUnifiedPriceSecondUnit() {
        // 统一价格（price_mode=1）：不区分视频输入，按输出分辨率匹配唯一规则行，按秒计量
        ModelPricingService.ModelPricing pricing = videoPricing(1, "second",
                List.of(new ModelPricingService.ModelPricing.VideoPriceRule("720P", null, new BigDecimal("0.20"))));
        when(pricingService.getPrice("doubao-seedance-2-0", null)).thenReturn(pricing);

        billingService.recordBillingSync(videoCtx("720P", false, new BigDecimal("5"), 108900L));

        ArgumentCaptor<BillingRecordEntity> captor = ArgumentCaptor.forClass(BillingRecordEntity.class);
        verify(billingRepository).save(captor.capture());
        BillingRecordEntity record = captor.getValue();

        assertThat(record.getServiceType()).isEqualTo("vidGen");
        // second 计费单位：token 列不落上游值（置 NULL，统计 SUM 聚合天然排除按秒计费记录）
        assertThat(record.getPromptTokens()).isNull();
        assertThat(record.getCompletionTokens()).isNull();
        assertThat(record.getTotalTokens()).isNull();
        // 计费 = 5 秒 × 0.20 元/秒
        assertThat(record.getOutputCost()).isEqualByComparingTo(new BigDecimal("1.000000"));
        assertThat(record.getOriginalCost()).isEqualByComparingTo(new BigDecimal("1.000000"));
        assertThat(record.getDiscountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(record.getTotalCost()).isEqualByComparingTo(new BigDecimal("1.000000"));
        // usage_detail 快照：second 单位 tokens 为 null，分项费用与账单一致
        assertThat(record.getUsageDetail())
                .contains("\"billingUnit\":\"second\"")
                .contains("\"resolution\":\"720P\"")
                .contains("\"hasVideoInput\":false")
                .contains("\"seconds\":5")
                .contains("\"tokens\":null")
                .contains("\"cost\":1.00")
                .contains("\"discountAmount\":0.00")
                .contains("\"amount\":1.00");
    }

    @Test
    void shouldMatchRuleByVideoInput_whenConditionalPricing() {
        // 按条件定价（price_mode=2）：按「输出分辨率 × 是否有视频输入」匹配规则行
        ModelPricingService.ModelPricing pricing = videoPricing(2, "second",
                List.of(
                        new ModelPricingService.ModelPricing.VideoPriceRule("720P", true, new BigDecimal("0.20")),
                        new ModelPricingService.ModelPricing.VideoPriceRule("720P", false, new BigDecimal("0.10"))));
        when(pricingService.getPrice("doubao-seedance-2-0", null)).thenReturn(pricing);

        billingService.recordBillingSync(videoCtx("720P", true, new BigDecimal("5"), 100L));
        billingService.recordBillingSync(videoCtx("720P", false, new BigDecimal("5"), 100L));

        ArgumentCaptor<BillingRecordEntity> captor = ArgumentCaptor.forClass(BillingRecordEntity.class);
        verify(billingRepository, times(2)).save(captor.capture());
        List<BillingRecordEntity> records = captor.getAllValues();

        // 有视频输入 → 0.20 元/秒
        assertThat(records.get(0).getOriginalCost()).isEqualByComparingTo(new BigDecimal("1.000000"));
        assertThat(records.get(0).getUsageDetail()).contains("\"hasVideoInput\":true");
        // 无视频输入 → 0.10 元/秒
        assertThat(records.get(1).getOriginalCost()).isEqualByComparingTo(new BigDecimal("0.500000"));
        assertThat(records.get(1).getUsageDetail()).contains("\"hasVideoInput\":false");
    }

    @Test
    void shouldRejectBilling_whenVideoRuleNotMatched() {
        // 资损兜底：无匹配启用规则行 → 拒计费（不落账、不扣余额）
        ModelPricingService.ModelPricing pricing = videoPricing(2, "second",
                List.of(new ModelPricingService.ModelPricing.VideoPriceRule("720P", true, new BigDecimal("0.20"))));
        when(pricingService.getPrice("doubao-seedance-2-0", null)).thenReturn(pricing);

        Long id = billingService.recordBillingSync(videoCtx("720P", false, new BigDecimal("5"), 100L));

        assertThat(id).isNull();
        verify(billingRepository, never()).save(any());
        verify(balanceDeductionService, never()).deductAndAlert(any(), any(), any(), any());
    }

    @Test
    void shouldRejectBilling_whenVideoSecondsMissing() {
        // 资损兜底：时长用量缺失（duration=-1 智能选择且响应未回传有效值）→ 拒计费
        ModelPricingService.ModelPricing pricing = videoPricing(1, "second",
                List.of(new ModelPricingService.ModelPricing.VideoPriceRule("720P", null, new BigDecimal("0.20"))));
        when(pricingService.getPrice("doubao-seedance-2-0", null)).thenReturn(pricing);

        Long id = billingService.recordBillingSync(videoCtx("720P", false, null, 100L));

        assertThat(id).isNull();
        verify(billingRepository, never()).save(any());
    }

    @Test
    void shouldRejectBilling_whenPricingMissing() {
        // 资损兜底：视频结算链路定价未命中（平台未上架/同步失败）→ 拒计费，绝不回落六维按 0 元落账
        when(pricingService.getPrice("doubao-seedance-2-0", null)).thenReturn(null);

        Long id = billingService.recordBillingSync(videoCtx("720P", false, new BigDecimal("5"), 100L));

        assertThat(id).isNull();
        verify(billingRepository, never()).save(any());
        verify(balanceDeductionService, never()).deductAndAlert(any(), any(), any(), any());
    }

    @Test
    void shouldRejectBilling_whenVideoRulePriceInvalid() {
        // 资损兜底：命中规则行价格非正数（平台配置疏忽）→ 拒计费，防止按 0 元落账
        ModelPricingService.ModelPricing pricing = videoPricing(1, "second",
                List.of(new ModelPricingService.ModelPricing.VideoPriceRule("720P", null, BigDecimal.ZERO)));
        when(pricingService.getPrice("doubao-seedance-2-0", null)).thenReturn(pricing);

        Long id = billingService.recordBillingSync(videoCtx("720P", false, new BigDecimal("5"), 100L));

        assertThat(id).isNull();
        verify(billingRepository, never()).save(any());
    }

    @Test
    void shouldRejectBilling_whenConditionalRuleHasNullVideoInput() {
        // 条件定价下规则行 has_video_input 为 NULL（平台误配）不参与匹配：
        // hasVideoInput=false 的任务也不会命中 null 行，拒计费留人工对账
        ModelPricingService.ModelPricing pricing = videoPricing(2, "second",
                List.of(new ModelPricingService.ModelPricing.VideoPriceRule("720P", null, new BigDecimal("0.20"))));
        when(pricingService.getPrice("doubao-seedance-2-0", null)).thenReturn(pricing);

        Long id = billingService.recordBillingSync(videoCtx("720P", false, new BigDecimal("5"), 100L));

        assertThat(id).isNull();
        verify(billingRepository, never()).save(any());
    }

    @Test
    void shouldBillVideoByTokens_whenTokenBillingUnit() {
        // 计费单位 token：按 token × 元/token 计量（元/M token 同步层已 ÷1e6 转换）
        ModelPricingService.ModelPricing pricing = videoPricing(1, "token",
                List.of(new ModelPricingService.ModelPricing.VideoPriceRule("1080P", null, new BigDecimal("0.000002"))));
        when(pricingService.getPrice("doubao-seedance-2-0", null)).thenReturn(pricing);

        billingService.recordBillingSync(videoCtx("1080P", false, null, 100000L));

        ArgumentCaptor<BillingRecordEntity> captor = ArgumentCaptor.forClass(BillingRecordEntity.class);
        verify(billingRepository).save(captor.capture());
        BillingRecordEntity record = captor.getValue();

        assertThat(record.getOriginalCost()).isEqualByComparingTo(new BigDecimal("0.200000"));
        assertThat(record.getTotalCost()).isEqualByComparingTo(new BigDecimal("0.200000"));
        // token 计费单位：completion/total 即计费依据，必须落库（prompt 恒 0）
        assertThat(record.getPromptTokens()).isZero();
        assertThat(record.getCompletionTokens()).isEqualTo(100000L);
        assertThat(record.getTotalTokens()).isEqualTo(100000L);
        // usage_detail 快照：token 单位 seconds 为 null、tokens 为 token 数
        assertThat(record.getUsageDetail())
                .contains("\"billingUnit\":\"token\"")
                .contains("\"seconds\":null")
                .contains("\"tokens\":100000");
    }

    @Test
    void shouldApplyDiscount_whenVideoBilling() {
        // 视频计费复用多级折扣（模型 × 用户 × 企业补贴）
        ModelPricingService.ModelPricing pricing = videoPricing(1, "second",
                List.of(new ModelPricingService.ModelPricing.VideoPriceRule("720P", null, new BigDecimal("0.20"))));
        when(pricingService.getPrice("doubao-seedance-2-0", null)).thenReturn(pricing);
        DiscountBreakdown discounts = new DiscountBreakdown(
                new BigDecimal("0.800000"), BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.800000"));
        when(discountCalculationService.calculate(any(UserIdentity.class), eq("doubao-seedance-2-0"), eq(null)))
                .thenReturn(discounts);

        billingService.recordBillingSync(videoCtx("720P", false, new BigDecimal("5"), 100L));

        ArgumentCaptor<BillingRecordEntity> captor = ArgumentCaptor.forClass(BillingRecordEntity.class);
        verify(billingRepository).save(captor.capture());
        BillingRecordEntity record = captor.getValue();

        // 原价 1.0，8 折后实付 0.8，折扣金额 0.2
        assertThat(record.getOriginalCost()).isEqualByComparingTo(new BigDecimal("1.000000"));
        assertThat(record.getDiscountAmount()).isEqualByComparingTo(new BigDecimal("0.200000"));
        assertThat(record.getTotalCost()).isEqualByComparingTo(new BigDecimal("0.800000"));
        assertThat(record.getUsageDetail())
                .contains("\"cost\":1.00")
                .contains("\"discountAmount\":0.20")
                .contains("\"amount\":0.80");
    }

    // ==================== model_id 与响应快照落库（V5） ====================

    @Test
    void shouldStoreModelId_whenPricingHit() {
        // 定价命中时 model_id 取定价缓存的 ai_model.id 快照，支持按模型主键精确关联模型表
        ModelPricingService.ModelPricing pricing = new ModelPricingService.ModelPricing(
                "gpt-4", null,
                new BigDecimal("0.001"),
                new BigDecimal("0.002"),
                BigDecimal.ONE,
                42L);
        when(pricingService.getPrice("gpt-4", null)).thenReturn(pricing);

        BillingService.BillingContext ctx = BillingService.BillingContext.create()
                .userId("u1")
                .modelName("gpt-4")
                .serviceType("chat")
                .promptTokens(10L)
                .completionTokens(10L)
                .totalTokens(20L)
                .isFreeQuota(true);

        billingService.recordBilling(ctx);

        ArgumentCaptor<BillingRecordEntity> captor = ArgumentCaptor.forClass(BillingRecordEntity.class);
        verify(billingRepository).save(captor.capture());
        assertThat(captor.getValue().getModelId()).isEqualTo(42L);
    }

    @Test
    void shouldStoreNullModelId_whenPricingMiss() {
        // 定价未命中（按 0 元落账的历史兜底）：model_id 为 NULL，可退化为 model_name 关联
        when(pricingService.getPrice("gpt-4", null)).thenReturn(null);

        BillingService.BillingContext ctx = BillingService.BillingContext.create()
                .userId("u1")
                .modelName("gpt-4")
                .serviceType("chat")
                .promptTokens(10L)
                .completionTokens(10L)
                .totalTokens(20L)
                .isFreeQuota(true);

        billingService.recordBilling(ctx);

        ArgumentCaptor<BillingRecordEntity> captor = ArgumentCaptor.forClass(BillingRecordEntity.class);
        verify(billingRepository).save(captor.capture());
        assertThat(captor.getValue().getModelId()).isNull();
    }

    @Test
    void shouldStoreResponseSnapshot_whenContextCarriesSnapshot() {
        ModelPricingService.ModelPricing pricing = new ModelPricingService.ModelPricing(
                "gpt-4", null,
                new BigDecimal("0.001"),
                new BigDecimal("0.002"),
                BigDecimal.ONE);
        when(pricingService.getPrice("gpt-4", null)).thenReturn(pricing);

        BillingService.BillingContext ctx = BillingService.BillingContext.create()
                .userId("u1")
                .modelName("gpt-4")
                .serviceType("chat")
                .promptTokens(10L)
                .completionTokens(10L)
                .totalTokens(20L)
                .responseSnapshot("{\"protocol\":\"openai\",\"success\":true}")
                .isFreeQuota(true);

        billingService.recordBilling(ctx);

        ArgumentCaptor<BillingRecordEntity> captor = ArgumentCaptor.forClass(BillingRecordEntity.class);
        verify(billingRepository).save(captor.capture());
        assertThat(captor.getValue().getResponseSnapshot())
                .isEqualTo("{\"protocol\":\"openai\",\"success\":true}");
    }

    @Test
    void shouldStoreNullSnapshot_whenContextHasNoSnapshot() {
        // ctx 未携带快照（构建失败/素材全空降级）：落库为 NULL，不影响落账主链路
        ModelPricingService.ModelPricing pricing = new ModelPricingService.ModelPricing(
                "gpt-4", null,
                new BigDecimal("0.001"),
                new BigDecimal("0.002"),
                BigDecimal.ONE);
        when(pricingService.getPrice("gpt-4", null)).thenReturn(pricing);

        BillingService.BillingContext ctx = BillingService.BillingContext.create()
                .userId("u1")
                .modelName("gpt-4")
                .serviceType("chat")
                .promptTokens(10L)
                .completionTokens(10L)
                .totalTokens(20L)
                .isFreeQuota(true);

        billingService.recordBilling(ctx);

        ArgumentCaptor<BillingRecordEntity> captor = ArgumentCaptor.forClass(BillingRecordEntity.class);
        verify(billingRepository).save(captor.capture());
        assertThat(captor.getValue().getResponseSnapshot()).isNull();
    }
}
