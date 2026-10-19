package org.unreal.modelrouter.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.unreal.modelrouter.billing.rule.*;
import org.unreal.modelrouter.billing.usage.*;
import org.unreal.modelrouter.persistence.jpa.entity.BillingRecordEntity;
import org.unreal.modelrouter.persistence.jpa.repository.BillingRecordRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformSystemUserRepository;

import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DoubaoAudioBillingTest {
    final ObjectMapper mapper = new ObjectMapper();
    final TokenUsageExtractor extractor = new TokenUsageExtractor(new VendorUsageStyleResolver(), mapper);
    final ModelPricingService prices = mock(ModelPricingService.class);
    final BillingRecordRepository records = mock(BillingRecordRepository.class);
    final DiscountCalculationService discounts = mock(DiscountCalculationService.class);
    final BalanceDeductionService balances = mock(BalanceDeductionService.class);
    BillingService service;
    BillingRecordEntity saved;
    ModelPricingService.ModelPricing pricing;

    @BeforeEach void setup() {
        service = new BillingService(prices, records, balances, mock(EnterpriseLookupService.class),
                mock(PlatformSystemUserRepository.class), discounts,
                new PricingRuleEngine(new ConditionTreeEvaluator(), mapper), mapper, mock(UserUsagePolicyService.class));
        lenient().when(discounts.calculate(any(), any(), any())).thenReturn(
                new DiscountBreakdown(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
        lenient().when(records.save(any())).thenAnswer(call -> { saved = call.getArgument(0); saved.setId(1L); return saved; });
        pricing = mock(ModelPricingService.ModelPricing.class);
        lenient().when(pricing.getPriceMode()).thenReturn(null); // Audio pricing has no video price mode.
        lenient().when(pricing.getBillingMode()).thenReturn(3);
        lenient().when(pricing.getThinkingBillingMode()).thenReturn(1);
        lenient().when(pricing.getThinkingPrice()).thenReturn(BigDecimal.ZERO);
        lenient().when(pricing.isEnableInputToken()).thenReturn(true);
        lenient().when(pricing.isEnableOutputToken()).thenReturn(true);
        lenient().when(pricing.isEnableCacheHitInput()).thenReturn(true);
        lenient().when(pricing.getRules()).thenReturn(rules());
        when(prices.getPrice("Doubao-Seed-2.0-lite", null)).thenReturn(pricing);
    }
    List<ModelPricingService.ModelPricing.PricingRuleData> rules() {
        return List.of(rule(1, "{\"field\":\"tokenCount\",\"op\":\"<=\",\"value\":32000}", ".6", ".12", "3.6", "9", "1.8"),
                rule(2, "{\"operator\":\"AND\",\"conditions\":[{\"field\":\"tokenCount\",\"op\":\">\",\"value\":32000},{\"field\":\"tokenCount\",\"op\":\"<=\",\"value\":128000}]}", ".9", ".18", "5.4", "13.5", "2.7"),
                rule(3, "{\"field\":\"tokenCount\",\"op\":\">\",\"value\":128000}", "1.8", ".36", "10.8", "27", "5.4"));
    }
    ModelPricingService.ModelPricing.PricingRuleData rule(long id, String match, String i, String c, String o, String a, String ac) {
        return new ModelPricingService.ModelPricing.PricingRuleData(id, "tier " + id, (int)id,
                match, "{\"normalPrice\":" + new BigDecimal(i) + ",\"cacheHit\":" + new BigDecimal(c)
                + ",\"output\":" + o + ",\"audioInput\":" + a + ",\"audioCacheHit\":" + ac + "}");
    }
    TokenUsage usage(long prompt, long cache, long audio, long audioCache, long output) throws Exception {
        return extractor.extract(mapper.readTree("{\"prompt_tokens\":" + prompt + ",\"completion_tokens\":" + output
                + ",\"prompt_tokens_details\":{\"cached_tokens\":" + cache + ",\"audio_tokens\":" + audio
                + ",\"audio_cached_tokens\":" + audioCache + "}}"), "ark", "https://ark.cn-beijing.volces.com");
    }
    BillingService.BillingContext context(TokenUsage usage) {
        return BillingService.BillingContext.create().modelName("Doubao-Seed-2.0-lite").serviceType("chat")
                .userId("test").tokenUsage(usage).audioInputRequested(true).isSuccess(true);
    }
    @Test void mixedTextAudioAndCacheAreDisjointAndSumToTheFullInput() throws Exception {
        var u = usage(1000000,250000,600000,150000,100000);
        assertThat(u.normalInput()).isEqualTo(300000);
        assertThat(u.cacheHit()).isEqualTo(100000);
        assertThat(u.audioInput()).isEqualTo(450000);
        assertThat(u.audioCacheHit()).isEqualTo(150000);
        assertThat(u.legacyPromptTokens()).isEqualTo(1000000);
        assertThat(u.billableTotal()).isEqualTo(1100000);
        assertThat(service.recordBillingSync(context(u))).isEqualTo(1);
        assertThat(saved.getInputBill()).isEqualByComparingTo("0.54");
        assertThat(saved.getCacheHitBill()).isEqualByComparingTo("0.03600000");
        assertThat(saved.getAudioInputBill()).isEqualByComparingTo("12.15");
        assertThat(saved.getAudioCacheHitBill()).isEqualByComparingTo("0.81");
        assertThat(saved.getOutputBill()).isEqualByComparingTo("1.08");
        assertThat(saved.getTotalCost()).isEqualByComparingTo("14.61600000");
        assertThat(saved.getPromptTokens()).isEqualTo(1000000);
        assertThat(saved.getAudioInputTokens()).isEqualTo(450000);
        assertThat(saved.getAudioCacheHitTokens()).isEqualTo(150000);
        assertThat(saved.getAudioInputUnitPrice()).isEqualByComparingTo("0.000027");
    }
    @ParameterizedTest @CsvSource({"32000,9,1.8", "32001,13.5,2.7", "128000,13.5,2.7", "128001,27,5.4"})
    void totalInputIncludingAudioCacheDeterminesTheInclusiveBoundary(long total, String a, String c) throws Exception {
        service.recordBillingSync(context(usage(total,total,total,total,0)));
        assertThat(saved.getAudioCacheHitTokens()).isEqualTo(total);
        assertThat(saved.getAudioInputTokens()).isZero();
        assertThat(saved.getCacheHitTokens()).isZero();
        assertThat(saved.getAudioInputUnitPrice()).isEqualByComparingTo(new BigDecimal(a).movePointLeft(6));
        assertThat(saved.getAudioCacheHitUnitPrice()).isEqualByComparingTo(new BigDecimal(c).movePointLeft(6));
        assertThat(saved.getTotalCost()).isEqualByComparingTo(BigDecimal.valueOf(total).multiply(new BigDecimal(c)).movePointLeft(6).setScale(8, java.math.RoundingMode.HALF_UP));
        service.recordBillingSync(context(usage(total,0,total,0,0)));
        assertThat(saved.getAudioInputTokens()).isEqualTo(total);
        assertThat(saved.getTotalCost()).isEqualByComparingTo(BigDecimal.valueOf(total).multiply(new BigDecimal(a)).movePointLeft(6).setScale(8, java.math.RoundingMode.HALF_UP));
    }
    @Test void audioReceivesTheSameDiscountAndFreeQuotaPolicyAsText() throws Exception {
        when(discounts.calculate(any(),any(),any())).thenReturn(new DiscountBreakdown(BigDecimal.ONE, new BigDecimal(".8"), BigDecimal.ONE, new BigDecimal(".8")));
        var u = usage(1000000,250000,600000,150000,100000);
        service.recordBillingSync(context(u));
        assertThat(saved.getTotalCost()).isEqualByComparingTo("11.69280000");
        assertThat(saved.getOriginalCost()).isEqualByComparingTo("14.61600000");
        assertThat(saved.getDiscountAmount()).isEqualByComparingTo("2.92320000");
        service.recordBillingSync(context(u).isFreeQuota(true));
        assertThat(saved.getTotalCost()).isZero();
        assertThat(saved.getUsageValue()).isEqualByComparingTo("11.69280000");
        assertThat(saved.getAudioInputBill()).isZero();
        assertThat(saved.getAudioCacheHitBill()).isZero();
        verifyNoInteractions(balances);
    }
    @Test void disabledCacheMergesIntoAudioInputRatherThanText() throws Exception {
        when(pricing.isEnableCacheHitInput()).thenReturn(false);
        service.recordBillingSync(context(usage(32000,32000,32000,32000,0)));
        assertThat(saved.getNormalInputTokens()).isZero();
        assertThat(saved.getAudioInputTokens()).isEqualTo(32000);
        assertThat(saved.getAudioCacheHitTokens()).isZero();
        assertThat(saved.getTotalCost()).isEqualByComparingTo(".28800000");
    }
    @Test void masterInputSwitchDisablesBothAudioDimensions() throws Exception {
        when(pricing.isEnableInputToken()).thenReturn(false);
        service.recordBillingSync(context(usage(32000,16000,32000,16000,0)));
        assertThat(saved.getAudioInputTokens()).isZero();
        assertThat(saved.getAudioCacheHitTokens()).isZero();
        assertThat(saved.getTotalCost()).isZero();
    }
    @Test void missingAudioBreakdownAndInconsistentIntersectionNeverCreatePartialBills() throws Exception {
        var missing = extractor.extract(mapper.readTree("{\"prompt_tokens\":32000,\"completion_tokens\":50}"), "ark", null);
        assertThat(service.recordBillingSync(context(missing))).isNull();
        for (TokenUsage invalid : List.of(usage(32000,100,20000,200,50), usage(32000,20000,100,200,50), usage(32000,20000,20000,0,50))) {
            assertThat(invalid.audioUsageValid()).isFalse();
            assertThat(service.recordBillingSync(context(invalid))).isNull();
        }
        verifyNoInteractions(records, balances);
    }
    @Test void audioPricePairMustBeComplete() throws Exception {
        when(pricing.getRules()).thenReturn(List.of(new ModelPricingService.ModelPricing.PricingRuleData(1L,"incomplete",1,
                "{\"operator\":\"AND\",\"conditions\":[]}", "{\"normalPrice\":0.6,\"audioInput\":9}")));
        assertThat(service.recordBillingSync(context(usage(1000,0,1000,0,0)))).isNull();
        verifyNoInteractions(records, balances);
    }
    @Test void textOnlyAndModelsWithoutAudioPricesKeepTheirExistingBilling() throws Exception {
        var text = extractor.extract(mapper.readTree("{\"prompt_tokens\":32000,\"completion_tokens\":10000,\"prompt_tokens_details\":{\"cached_tokens\":8000}}"), "ark", null);
        service.recordBillingSync(context(text).audioInputRequested(false));
        assertThat(saved.getTotalCost()).isEqualByComparingTo(".05136000");
        assertThat(saved.getAudioInputTokens()).isZero();
        when(pricing.getRules()).thenReturn(List.of(new ModelPricingService.ModelPricing.PricingRuleData(1L,"generic",1,
                "{\"operator\":\"AND\",\"conditions\":[]}", "{\"normalPrice\":0.6,\"cacheHit\":0.12,\"output\":3.6}")));
        service.recordBillingSync(context(usage(32000,8000,20000,5000,10000)));
        assertThat(saved.getTotalCost()).isEqualByComparingTo(".05136000");
        assertThat(saved.getNormalInputTokens()).isEqualTo(24000);
        assertThat(saved.getCacheHitTokens()).isEqualTo(8000);
    }
    @Test void streamingChunkAndResponsesUsageUseTheSameNormalization() throws Exception {
        var u = extractor.extractFromChunk("{\"usage\":{\"prompt_tokens\":10000,\"completion_tokens\":1000,\"prompt_tokens_details\":{\"cached_tokens\":2500,\"audio_tokens\":6000,\"audio_cached_tokens\":1500}}}","doubao",null);
        assertThat(u.normalInput()).isEqualTo(3000);
        service.recordBillingSync(context(u));
        assertThat(saved.getTotalCost()).isEqualByComparingTo(".04872000");
        var responses = extractor.extract(mapper.readTree("{\"input_tokens\":10000,\"output_tokens\":1000,\"input_tokens_details\":{\"cached_tokens\":2500,\"audio_tokens\":6000,\"audio_cached_tokens\":1500}}"),"ark",null);
        assertThat(responses).isEqualTo(u);
    }
    @Test void audioRequestDetectionIgnoresPlainText() throws Exception {
        assertThat(RequestBillingMetadata.hasAudioInput(mapper.readTree("{\"messages\":[{\"content\":[{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"redacted\",\"format\":\"wav\"}}]}]}"))).isTrue();
        assertThat(RequestBillingMetadata.hasAudioInput(mapper.readTree("{\"input\":[{\"content\":[{\"type\":\"audio_url\",\"audio_url\":\"https://example.com/a.wav\"}]}]}"))).isTrue();
        assertThat(RequestBillingMetadata.hasAudioInput(mapper.readTree("{\"messages\":[{\"content\":\"input_audio is a word\"}]}"))).isFalse();
    }
}
