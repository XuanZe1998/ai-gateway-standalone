package org.unreal.modelrouter.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.platform.sync.PlatformDataSyncService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link ModelPricingService} 视频计费规则快照（snapshotVideoPricing）测试。
 */
@ExtendWith(MockitoExtension.class)
class ModelPricingServiceTest {

    private static final String MODEL = "doubao-seedance-2-0-260128";
    private static final String CHANNEL = "19";

    @Mock
    private PlatformDataSyncService platformDataSyncService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ModelPricingService pricingService() {
        return new ModelPricingService(platformDataSyncService, objectMapper);
    }

    private ModelPricingService.ModelPricing videoPricing(final int priceMode,
                                                          final List<ModelPricingService.ModelPricing.VideoPriceRule> rules) {
        return new ModelPricingService.ModelPricing(MODEL, CHANNEL, 37L, "volcengine", 1,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                false, false, false, false, false,
                1, BigDecimal.ZERO, BigDecimal.ONE, List.of(),
                priceMode, "second", rules);
    }

    @Test
    void snapshotVideoPricing_withoutUsableRules_returnsNull() {
        // 模型已配 priceMode 但规则行为空（未录入/全被同步层过滤）：快照置空，结算回退实时定价
        // （防止锁死空快照导致平台后续补录规则也无法补救的漏计费回归）
        when(platformDataSyncService.getModelPricing(MODEL))
                .thenReturn(Optional.of(videoPricing(2, List.of())));
        ModelPricingService service = pricingService();
        service.syncPricingFromPlatform(MODEL);

        assertThat(service.snapshotVideoPricing(MODEL, CHANNEL)).isNull();
    }

    @Test
    void snapshotVideoPricing_withUsableRules_returnsJson() {
        when(platformDataSyncService.getModelPricing(MODEL))
                .thenReturn(Optional.of(videoPricing(2, List.of(
                        new ModelPricingService.ModelPricing.VideoPriceRule(
                                "480P", false, new BigDecimal("46.0"))))));
        ModelPricingService service = pricingService();
        service.syncPricingFromPlatform(MODEL);

        String snapshot = service.snapshotVideoPricing(MODEL, CHANNEL);
        assertThat(snapshot).isNotNull()
                .contains("\"priceMode\":2")
                .contains("\"480P\"")
                .contains("\"modelId\":37");
    }

    @Test
    void snapshotVideoPricing_pricingMissing_returnsNull() {
        // 定价缓存未命中：快照置空，结算回退实时定价（兼容历史数据）
        when(platformDataSyncService.getModelPricing(MODEL)).thenReturn(Optional.empty());
        ModelPricingService service = pricingService();
        service.syncPricingFromPlatform(MODEL);

        assertThat(service.snapshotVideoPricing(MODEL, CHANNEL)).isNull();
    }
}
