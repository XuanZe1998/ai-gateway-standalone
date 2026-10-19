package org.unreal.modelrouter.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.billing.rule.*;
import org.unreal.modelrouter.billing.usage.RequestBillingMetadata;
import org.unreal.modelrouter.persistence.jpa.entity.BillingRecordEntity;
import org.unreal.modelrouter.persistence.jpa.repository.BillingRecordRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformSystemUserRepository;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MultimodalScreenshotBillingTest {
    ObjectMapper mapper = new ObjectMapper();
    ModelPricingService prices = mock(ModelPricingService.class);
    BillingRecordRepository records = mock(BillingRecordRepository.class);
    DiscountCalculationService discounts = mock(DiscountCalculationService.class);
    final UserUsagePolicyService policies = mock(UserUsagePolicyService.class);
    BillingService service;
    BillingRecordEntity saved;

    @BeforeEach void setup() {
        service = new BillingService(prices, records, mock(BalanceDeductionService.class),
                mock(EnterpriseLookupService.class), mock(PlatformSystemUserRepository.class), discounts,
                new PricingRuleEngine(new ConditionTreeEvaluator(), mapper), mapper, policies);
        lenient().when(discounts.calculate(any(), any(), any())).thenReturn(new DiscountBreakdown(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE));
        lenient().when(records.save(any())).thenAnswer(call -> { saved=call.getArgument(0); saved.setId(123L); return saved; });
    }
    ModelPricingService.ModelPricing pricing(Integer videoMode, List<ModelPricingService.ModelPricing.VideoPriceRule> videos,
                                           List<ModelPricingService.ModelPricing.PricingRuleData> rules) {
        return new ModelPricingService.ModelPricing("model",null,1L,"vendor",3,
                BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,
                true,true,true,false,false,1,BigDecimal.ZERO,BigDecimal.ONE,List.of(),videoMode,
                videoMode == null ? null : "second",videos,rules);
    }
    BillingService.BillingContext context() { return BillingService.BillingContext.create().modelName("model").userId("test"); }
    @Test void freeImagesAndVideosKeepNormalUsageValueWithoutChargingMoney() {
        when(policies.isFreeUsage("test")).thenReturn(true);
        var image=new ModelPricingService.ModelPricing.PricingRuleData(1L,"image",10,"{\"operator\":\"AND\",\"conditions\":[]}","{\"imagePrice\":0.25}");
        when(prices.getPrice("model",null)).thenReturn(pricing(null,List.of(),List.of(image)));
        service.recordBillingSync(context().platformUser(true).serviceType("imgGen").imageCount(2L));
        assertThat(saved.getTotalCost()).isZero();assertThat(saved.getUsageValue()).isEqualByComparingTo("0.50");
        var rate=new ModelPricingService.ModelPricing.VideoPriceRule("768P",null,new BigDecimal("0.5"),new BigDecimal("0.5"));
        when(prices.getPrice("model",null)).thenReturn(pricing(1,List.of(rate),List.of()));
        service.recordBillingSync(context().platformUser(true).serviceType("vidGen").videoUsage(new BillingService.VideoUsage("768P",true,new BigDecimal("5"),0L,new BigDecimal("3"))));
        assertThat(saved.getTotalCost()).isZero();assertThat(saved.getUsageValue()).isEqualByComparingTo("4.00");assertThat(saved.getTotalTokens()).isNull();
    }
    @Test void fixedAdjustmentUsesImageAndVideoQuoteUnitsBeforeAccountDiscount() {
        when(discounts.calculate(any(), any(), any())).thenReturn(new DiscountBreakdown(
                BigDecimal.ONE, new BigDecimal("0.8"), BigDecimal.ONE, new BigDecimal("0.8"), new BigDecimal("0.12345678")));
        var image = new ModelPricingService.ModelPricing.PricingRuleData(1L, "image", 10,
                "{\"operator\":\"AND\",\"conditions\":[]}", "{\"imagePrice\":0.25}");
        when(prices.getPrice("model", null)).thenReturn(pricing(null, List.of(), List.of(image)));
        service.recordBillingSync(context().serviceType("imgGen").imageCount(2L));
        assertThat(saved.getTotalCost()).isEqualByComparingTo("0.59753085");
        assertThat(saved.getOriginalCost()).isEqualByComparingTo("0.50000000");
        var video = new ModelPricingService.ModelPricing.VideoPriceRule("720P", null, new BigDecimal("0.5"), BigDecimal.ZERO);
        when(prices.getPrice("model", null)).thenReturn(pricing(1, List.of(video), List.of()));
        service.recordBillingSync(context().serviceType("vidGen").videoUsage(
                new BillingService.VideoUsage("720P", true, new BigDecimal("5"), 0L, new BigDecimal("3"))));
        assertThat(saved.getInputBill()).isEqualByComparingTo("0.29629627");
        assertThat(saved.getOutputBill()).isEqualByComparingTo("2.49382712");
        assertThat(saved.getTotalCost()).isEqualByComparingTo("2.79012339");
        assertThat(saved.getFixedAdjustment()).isEqualByComparingTo("0.12345678");
    }
    @Test void imagesBillActualOutputCountWithNoTokenCounts() throws Exception {
        var rule = new ModelPricingService.ModelPricing.PricingRuleData(1L,"image",10,
                "{\"operator\":\"AND\",\"conditions\":[]}","{\"imagePrice\":0.25}");
        when(prices.getPrice("model",null)).thenReturn(pricing(null,List.of(),List.of(rule)));
        long count = RequestBillingMetadata.imageCount(mapper.readTree("{\"data\":[{\"url\":\"a\"},{\"b64_json\":\"b\"},{\"error\":{}}]}"));
        assertThat(service.recordBillingSync(context().serviceType("imgGen").imageCount(count))).isEqualTo(123L);
        assertThat(saved.getTotalCost()).isEqualByComparingTo("0.50");
        assertThat(saved.getTotalTokens()).isNull();
        assertThat(mapper.readTree(saved.getUsageDetail()).path("items").get(0).path("count").asInt()).isEqualTo(2);
    }
    @Test void missingImagesRefuseSettlement() {
        var rule = new ModelPricingService.ModelPricing.PricingRuleData(1L,"image",10,
                "{\"operator\":\"AND\",\"conditions\":[]}","{\"imagePrice\":0.25}");
        when(prices.getPrice("model",null)).thenReturn(pricing(null,List.of(),List.of(rule)));
        assertThat(service.recordBillingSync(context().serviceType("imgGen").imageCount(0L))).isNull();
        verify(records,never()).save(any());
    }
    @Test void videoInputAndOutputHaveSeparateDurationsAndPrices() {
        var rate=new ModelPricingService.ModelPricing.VideoPriceRule("768P",null,new BigDecimal("0.5"),new BigDecimal("0.5"));
        when(prices.getPrice("model",null)).thenReturn(pricing(1,List.of(rate),List.of()));
        var usage=new BillingService.VideoUsage("768P",true,new BigDecimal("5"),0L,new BigDecimal("3"));
        assertThat(service.recordBillingSync(context().serviceType("vidGen").videoUsage(usage))).isEqualTo(123L);
        assertThat(saved.getInputCost()).isEqualByComparingTo("1.50");
        assertThat(saved.getOutputCost()).isEqualByComparingTo("2.50");
        assertThat(saved.getTotalCost()).isEqualByComparingTo("4.00");
    }
    @Test void explicitOtherPriceDoesNotOverrideExactResolution() {
        var fallback=new ModelPricingService.ModelPricing.VideoPriceRule("OTHER",null,BigDecimal.ZERO);
        var exact=new ModelPricingService.ModelPricing.VideoPriceRule("720P",null,new BigDecimal("0.9"));
        when(prices.getPrice("model",null)).thenReturn(pricing(1,List.of(fallback,exact),List.of()));
        service.recordBillingSync(context().videoUsage(new BillingService.VideoUsage("720P",false,new BigDecimal("5"),0L)));
        assertThat(saved.getTotalCost()).isEqualByComparingTo("4.50");
        service.recordBillingSync(context().videoUsage(new BillingService.VideoUsage("480P",false,new BigDecimal("5"),0L)));
        assertThat(saved.getTotalCost()).isEqualByComparingTo("0.00");
    }
    @Test void missingInputDurationDoesNotProducePartialBill() {
        var rate=new ModelPricingService.ModelPricing.VideoPriceRule("768P",null,new BigDecimal("0.5"),new BigDecimal("0.5"));
        when(prices.getPrice("model",null)).thenReturn(pricing(1,List.of(rate),List.of()));
        assertThat(service.recordBillingSync(context().videoUsage(new BillingService.VideoUsage("768P",true,new BigDecimal("5"),0L)))).isNull();
        verify(records,never()).save(any());
    }
    @Test void responseTierWinsAndRequestPriorityIsSupported() throws Exception {
        var request=mapper.readTree("{\"service_tier\":\"priority\"}");
        assertThat(RequestBillingMetadata.fastMode(request,null)).isTrue();
        assertThat(RequestBillingMetadata.fastMode(request,mapper.readTree("{\"service_tier\":\"standard\"}"))).isFalse();
        assertThat(RequestBillingMetadata.fastMode(mapper.readTree("{}"),null)).isFalse();
    }
    @Test void fastContextSelectsPriorityPriceAt512kBoundary() {
        var fast=new ModelPricingService.ModelPricing.PricingRuleData(1L,"Fast",10,
                "{\"operator\":\"AND\",\"conditions\":[{\"field\":\"fastMode\",\"op\":\"==\",\"value\":true},{\"field\":\"tokenCount\",\"op\":\">=\",\"value\":512000}]}",
                "{\"normalPrice\":6.3,\"output\":25.2}");
        var standard=new ModelPricingService.ModelPricing.PricingRuleData(2L,"standard",100,
                "{\"operator\":\"AND\",\"conditions\":[]}","{\"normalPrice\":4.2,\"output\":16.8}");
        when(prices.getPrice("model",null)).thenReturn(pricing(null,List.of(),List.of(fast,standard)));
        service.recordBillingSync(context().serviceType("chat").promptTokens(512000L).completionTokens(0L).fastMode(true));
        assertThat(saved.getTotalCost()).isEqualByComparingTo("3.22560000");
        service.recordBillingSync(context().serviceType("chat").promptTokens(512000L).completionTokens(0L));
        assertThat(saved.getTotalCost()).isEqualByComparingTo("2.15040000");
    }
    @Test void videoInputMetadataCoversContentAndReferenceUrls() throws Exception {
        assertThat(org.unreal.modelrouter.router.video.VideoTaskArchiver.hasVideoInputInRequest(mapper.readTree("{\"prompt\":\"example\"}"))).isFalse();
        assertThat(org.unreal.modelrouter.router.video.VideoTaskArchiver.hasVideoInputInRequest(mapper.readTree("{\"input\":{\"video_urls\":[\"https://example.com/video\"]}}"))).isTrue();
        assertThat(RequestBillingMetadata.inputVideoSeconds(mapper.readTree("{\"usage\":{\"input_video_duration\":3.5}}"))).isEqualByComparingTo("3.5");
        assertThat(RequestBillingMetadata.inputVideoSeconds(mapper.readTree("{\"usage\":{\"input_video_duration\":-2}}"))).isNull();
    }
    @Test void administratorFreePolicyAppliesToImagesAndDelayedVideoSettlement() {
        when(policies.isFreeUsage("test")).thenReturn(true);
        var rule = new ModelPricingService.ModelPricing.PricingRuleData(1L, "image", 10,
                "{\"operator\":\"AND\",\"conditions\":[]}", "{\"imagePrice\":0.25}");
        when(prices.getPrice("model", null)).thenReturn(pricing(null, List.of(), List.of(rule)));
        assertThat(service.recordBillingSync(context().platformUser(true).serviceType("imgGen").imageCount(2L))).isEqualTo(123L);
        assertThat(saved.getTotalCost()).isZero();
        assertThat(saved.getIsAdminFreeUsage()).isTrue();
        var rate = new ModelPricingService.ModelPricing.VideoPriceRule("720P", null, new BigDecimal("0.5"));
        when(prices.getPrice("model", null)).thenReturn(pricing(1, List.of(rate), List.of()));
        assertThat(service.recordBillingSync(context().platformUser(true).serviceType("vidGen").videoUsage(
                new BillingService.VideoUsage("720P", false, new BigDecimal("5"), 0L)))).isEqualTo(123L);
        assertThat(saved.getTotalCost()).isZero();
        assertThat(saved.getIsAdminFreeUsage()).isTrue();
    }
}
