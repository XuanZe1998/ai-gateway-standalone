package org.unreal.modelrouter.catalog;

import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.billing.DiscountBreakdown;
import org.unreal.modelrouter.billing.ModelPricingService.ModelPricing;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformModelEntity;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformModelPriceTierEntity;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelSquarePricingTest {
    final ModelSquarePricing presenter = new ModelSquarePricing();
    final DiscountBreakdown discount = new DiscountBreakdown(new BigDecimal("0.9"), new BigDecimal("0.8"), new BigDecimal("0.5"), new BigDecimal("0.4"));
    ModelPricing price() {
        ModelPricing p = mock(ModelPricing.class);
        when(p.getBillingMode()).thenReturn(1);
        when(p.getInputPrice()).thenReturn(new BigDecimal("0.0000025"));
        when(p.getOutputPrice()).thenReturn(new BigDecimal("0.000005"));
        when(p.isEnableInputToken()).thenReturn(true);
        when(p.isEnableOutputToken()).thenReturn(true);
        when(p.getThinkingBillingMode()).thenReturn(1);
        return p;
    }
    @Test void decimalUnitConversionAndCurrentDiscount() {
        var scheme = presenter.present("chat", price(), discount, null, List.of());
        assertTrue(scheme.configured());
        assertEquals(0, new BigDecimal("2.5").compareTo(scheme.lines().get(0).standardPrice()));
        assertEquals(0, BigDecimal.ONE.compareTo(scheme.lines().get(0).effectivePrice()));
        assertEquals("IN_OUTPUT", scheme.lines().get(5).status());
        assertEquals("NOT_CHARGED", scheme.lines().get(2).status());
        assertEquals("元 / 百万 Token", scheme.lines().get(0).unit());
    }
    @Test void missingIsNotFreeButExplicitZeroIs() {
        var p = price(); when(p.getInputPrice()).thenReturn(BigDecimal.ZERO);
        assertEquals("UNKNOWN", presenter.present("chat", p, discount, null, List.of()).lines().get(0).status());
        var raw = new PlatformModelEntity(); raw.setInputPrice(BigDecimal.ZERO);
        var scheme = presenter.present("chat", p, discount, raw, List.of());
        assertEquals("CHARGED", scheme.lines().get(0).status());
        assertEquals(BigDecimal.ZERO, scheme.lines().get(0).effectivePrice());
        raw.setInputPrice(BigDecimal.ONE); // raw has changed but cached zero is not confirmed
        assertEquals("UNKNOWN", presenter.present("chat", p, discount, raw, List.of()).lines().get(0).status());
        assertFalse(presenter.present("chat", null, discount, raw, List.of()).configured());
    }
    @Test void masterSwitchesDisableCacheAndThinking() {
        var p = price(); when(p.isEnableInputToken()).thenReturn(false); when(p.isEnableOutputToken()).thenReturn(false);
        when(p.isEnableCacheHitInput()).thenReturn(true); when(p.isEnableCacheCreateInput()).thenReturn(true);
        when(p.isEnableCacheHitExplicitInput()).thenReturn(true); when(p.getThinkingBillingMode()).thenReturn(2);
        assertTrue(presenter.present("chat", p, discount, null, List.of()).lines().stream().allMatch(r -> "NOT_CHARGED".equals(r.status())));
    }
    @Test void thinkingModesAndCachePrices() {
        var p = price(); when(p.getThinkingBillingMode()).thenReturn(2); when(p.getThinkingPrice()).thenReturn(new BigDecimal("0.000003"));
        when(p.isEnableCacheHitInput()).thenReturn(true); when(p.getCacheHitInputPrice()).thenReturn(new BigDecimal("0.000001"));
        var rows = presenter.present("chat", p, discount, null, List.of()).lines();
        assertEquals("CHARGED", rows.get(5).status()); assertEquals(0, new BigDecimal("1.2").compareTo(rows.get(5).effectivePrice()));
        assertEquals(0, new BigDecimal("0.4").compareTo(rows.get(2).effectivePrice()));
        when(p.getThinkingBillingMode()).thenReturn(3);
        assertEquals("NOT_CHARGED", presenter.present("chat", p, discount, null, List.of()).lines().get(5).status());
    }
    @Test void tiersUseActualTokenBoundariesAndMatchRawByBoundsNotIndex() {
        var p = price(); when(p.getBillingMode()).thenReturn(2);
        var low = new ModelPricing.PriceTier(BigDecimal.ZERO, new BigDecimal("128000"), false, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.000005"), BigDecimal.ZERO, BigDecimal.ZERO);
        var high = new ModelPricing.PriceTier(new BigDecimal("128000"), null, true, new BigDecimal("0.000004"), BigDecimal.ZERO, new BigDecimal("0.000008"), BigDecimal.ZERO, BigDecimal.ZERO);
        when(p.getTiers()).thenReturn(List.of(low, high));
        var rawLow = new PlatformModelPriceTierEntity(); rawLow.setTierLowerLimit(BigDecimal.ZERO); rawLow.setTierUpperLimit(new BigDecimal("128")); rawLow.setIsUnlimited(false); rawLow.setInputPrice(BigDecimal.ZERO);
        var rawHigh = new PlatformModelPriceTierEntity(); rawHigh.setTierLowerLimit(new BigDecimal("128")); rawHigh.setIsUnlimited(true);
        var scheme = presenter.present("chat", p, discount, null, List.of(rawHigh, rawLow));
        assertTrue(scheme.configured()); assertTrue(scheme.lines().get(0).condition().contains("[0, 128000)"));
        assertTrue(scheme.lines().get(6).condition().contains("[128000, ∞)"));
        assertEquals("CHARGED", scheme.lines().get(0).status());
        when(p.getTiers()).thenReturn(List.of()); assertFalse(presenter.present("chat", p, discount, null, List.of()).configured());
    }
    @Test void videoConditionsUnitsAndMissingRules() {
        var p = price(); when(p.getPriceMode()).thenReturn(2); when(p.getBillingUnit()).thenReturn("second");
        when(p.getVideoPriceRules()).thenReturn(List.of(new ModelPricing.VideoPriceRule("720P", false, new BigDecimal("0.8")), new ModelPricing.VideoPriceRule("1080P", true, new BigDecimal("1.2"))));
        var scheme = presenter.present("vidGen", p, discount, null, List.of());
        assertEquals("元 / 秒", scheme.lines().get(0).unit()); assertTrue(scheme.lines().get(0).condition().contains("无视频输入"));
        assertTrue(scheme.lines().get(1).condition().contains("有视频输入")); assertEquals(0, new BigDecimal("0.32").compareTo(scheme.lines().get(0).effectivePrice()));
        when(p.getBillingUnit()).thenReturn("token"); when(p.getVideoPriceRules()).thenReturn(List.of(new ModelPricing.VideoPriceRule("720P", false, new BigDecimal("0.00001"))));
        assertEquals(0, BigDecimal.TEN.compareTo(presenter.present("vidGen", p, discount, null, List.of()).lines().get(0).standardPrice()));
        when(p.getBillingUnit()).thenReturn("frame"); assertFalse(presenter.present("vidGen", p, discount, null, List.of()).configured());
        when(p.getBillingUnit()).thenReturn("second"); when(p.getVideoPriceRules()).thenReturn(List.of(new ModelPricing.VideoPriceRule("720P", null, BigDecimal.ONE)));
        assertFalse(presenter.present("vidGen", p, discount, null, List.of()).configured());
        when(p.getVideoPriceRules()).thenReturn(List.of()); assertFalse(presenter.present("vidGen", p, discount, null, List.of()).configured());
    }
}
