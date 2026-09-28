// 文件说明：测试 DiscountCalculationServiceTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.persistence.jpa.entity.billing.UserModelDiscountEntity;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformEnterpriseEntity;
import org.unreal.modelrouter.persistence.jpa.repository.billing.UserModelDiscountRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformEnterpriseRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiscountCalculationServiceTest {

    @Mock
    private ModelPricingService modelPricingService;

    @Mock
    private UserModelDiscountRepository userModelDiscountRepository;

    @Mock
    private PlatformEnterpriseRepository enterpriseRepository;

    private DiscountCalculationService service;

    @BeforeEach
    void setUp() {
        service = new DiscountCalculationService(modelPricingService, userModelDiscountRepository, enterpriseRepository);
    }

    @Test
    void personalUser_withModelAndUserDiscount() {
        UserIdentity identity = new UserIdentity(
                "u1", "u1", "k1", "key", null, null, null, true, 2, 100L, 2);

        when(modelPricingService.getPrice("gpt-4", "ch1"))
                .thenReturn(new ModelPricingService.ModelPricing("gpt-4", "ch1",
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.90"), 1L));
        UserModelDiscountEntity discount = new UserModelDiscountEntity();
        discount.setUserType(2);
        discount.setUserId("100");
        discount.setModelId(1L);
        discount.setDiscount(85);
        when(userModelDiscountRepository.findFirstByUserTypeAndUserIdAndModelIdAndDeletedFalse(2, "100", 1L))
                .thenReturn(Optional.of(discount));

        DiscountBreakdown result = service.calculate(identity, "gpt-4", "ch1");

        assertThat(result.modelDiscountRate()).isEqualByComparingTo(new BigDecimal("0.900000"));
        assertThat(result.userDiscountRate()).isEqualByComparingTo(new BigDecimal("0.850000"));
        assertThat(result.enterpriseDiscountRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.finalDiscountRate()).isEqualByComparingTo(new BigDecimal("0.850000"));
    }

    @Test
    void enterpriseUser_withAllDiscounts() {
        UserIdentity identity = new UserIdentity(
                "u1", "u1", "k1", "key", 10L, "ent", "C001", true, 1, 100L, 4);

        when(modelPricingService.getPrice("gpt-4", null))
                .thenReturn(new ModelPricingService.ModelPricing("gpt-4", null,
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.90"), 1L));
        UserModelDiscountEntity discount = new UserModelDiscountEntity();
        discount.setUserType(1);
        discount.setUserId("C001");
        discount.setModelId(1L);
        discount.setDiscount(90);
        when(userModelDiscountRepository.findFirstByUserTypeAndUserIdAndModelIdAndDeletedFalse(1, "C001", 1L))
                .thenReturn(Optional.of(discount));
        PlatformEnterpriseEntity enterprise = new PlatformEnterpriseEntity();
        enterprise.setId(10L);
        enterprise.setSubsidyDiscount(80);
        when(enterpriseRepository.findById(10L)).thenReturn(Optional.of(enterprise));

        DiscountBreakdown result = service.calculate(identity, "gpt-4", null);

        assertThat(result.modelDiscountRate()).isEqualByComparingTo(new BigDecimal("0.900000"));
        assertThat(result.userDiscountRate()).isEqualByComparingTo(new BigDecimal("0.900000"));
        assertThat(result.enterpriseDiscountRate()).isEqualByComparingTo(new BigDecimal("0.800000"));
        assertThat(result.finalDiscountRate()).isEqualByComparingTo(new BigDecimal("0.720000"));
    }

    @Test
    void localUser_noDiscount() {
        UserIdentity identity = new UserIdentity(
                "local", "local", null, null, null, null, null, false, null, null, null);

        when(modelPricingService.getPrice("gpt-4", null))
                .thenReturn(new ModelPricingService.ModelPricing("gpt-4", null,
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.90"), 1L));

        DiscountBreakdown result = service.calculate(identity, "gpt-4", null);

        assertThat(result.modelDiscountRate()).isEqualByComparingTo(new BigDecimal("0.900000"));
        assertThat(result.userDiscountRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.enterpriseDiscountRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.finalDiscountRate()).isEqualByComparingTo(new BigDecimal("0.900000"));
        verifyNoInteractions(userModelDiscountRepository, enterpriseRepository);
    }

    @Test
    void missingUserDiscount_defaultsToNoDiscount() {
        UserIdentity identity = new UserIdentity(
                "u1", "u1", "k1", "key", null, null, null, true, 2, 100L, 2);

        when(modelPricingService.getPrice("gpt-4", null))
                .thenReturn(new ModelPricingService.ModelPricing("gpt-4", null,
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.90"), 1L));
        when(userModelDiscountRepository.findFirstByUserTypeAndUserIdAndModelIdAndDeletedFalse(2, "100", 1L))
                .thenReturn(Optional.empty());

        DiscountBreakdown result = service.calculate(identity, "gpt-4", null);

        assertThat(result.userDiscountRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.finalDiscountRate()).isEqualByComparingTo(new BigDecimal("0.900000"));
    }

    @Test
    void negativePercentageDiscount_defaultsToNoDiscount() {
        UserIdentity identity = new UserIdentity(
                "u1", "u1", "k1", "key", 10L, "ent", "C001", true, 1, 100L, 4);

        when(modelPricingService.getPrice("gpt-4", null))
                .thenReturn(new ModelPricingService.ModelPricing("gpt-4", null,
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.90"), 1L));
        UserModelDiscountEntity discount = new UserModelDiscountEntity();
        discount.setUserType(1);
        discount.setUserId("C001");
        discount.setModelId(1L);
        discount.setDiscount(-10);
        when(userModelDiscountRepository.findFirstByUserTypeAndUserIdAndModelIdAndDeletedFalse(1, "C001", 1L))
                .thenReturn(Optional.of(discount));

        DiscountBreakdown result = service.calculate(identity, "gpt-4", null);

        assertThat(result.userDiscountRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.finalDiscountRate()).isEqualByComparingTo(new BigDecimal("0.900000"));
    }

    @Test
    void discountSetForDifferentModel_defaultsToNoDiscount() {
        UserIdentity identity = new UserIdentity(
                "u1", "u1", "k1", "key", null, null, null, true, 2, 100L, 2);

        when(modelPricingService.getPrice("gpt-4", null))
                .thenReturn(new ModelPricingService.ModelPricing("gpt-4", null,
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.90"), 1L));
        UserModelDiscountEntity discount = new UserModelDiscountEntity();
        discount.setUserType(2);
        discount.setUserId("100");
        discount.setModelId(999L);
        discount.setDiscount(85);
        when(userModelDiscountRepository.findFirstByUserTypeAndUserIdAndModelIdAndDeletedFalse(2, "100", 1L))
                .thenReturn(Optional.empty());

        DiscountBreakdown result = service.calculate(identity, "gpt-4", null);

        assertThat(result.userDiscountRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.finalDiscountRate()).isEqualByComparingTo(new BigDecimal("0.900000"));
    }

    @Test
    void missingModelId_defaultsToNoDiscount() {
        UserIdentity identity = new UserIdentity(
                "u1", "u1", "k1", "key", null, null, null, true, 2, 100L, 2);

        when(modelPricingService.getPrice("gpt-4", null))
                .thenReturn(new ModelPricingService.ModelPricing("gpt-4", null,
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.90")));

        DiscountBreakdown result = service.calculate(identity, "gpt-4", null);

        assertThat(result.userDiscountRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.finalDiscountRate()).isEqualByComparingTo(new BigDecimal("0.900000"));
        verifyNoInteractions(userModelDiscountRepository);
    }
}
