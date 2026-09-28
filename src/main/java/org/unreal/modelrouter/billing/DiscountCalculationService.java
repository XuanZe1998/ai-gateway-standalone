// 文件说明：DiscountCalculationService：负责计费与余额管理中的组件实现。
package org.unreal.modelrouter.billing;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.persistence.jpa.entity.billing.UserModelDiscountEntity;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformEnterpriseEntity;
import org.unreal.modelrouter.persistence.jpa.repository.billing.UserModelDiscountRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformEnterpriseRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DiscountCalculationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscountCalculationService.class);
    private static final BigDecimal NO_DISCOUNT = BigDecimal.ONE;
    private static final int SCALE = 6;

    private final ModelPricingService modelPricingService;
    private final UserModelDiscountRepository userModelDiscountRepository;
    private final PlatformEnterpriseRepository enterpriseRepository;

    public DiscountBreakdown calculate(UserIdentity identity, String modelName, String channelId) {
        ModelPricingService.ModelPricing pricing = tryGetPricing(modelName, channelId);
        BigDecimal modelRate = resolveModelDiscountRate(pricing);
        Long modelId = pricing != null ? pricing.getModelId() : null;
        BigDecimal userRate = resolveUserDiscountRate(identity, modelId);
        BigDecimal enterpriseRate = resolveEnterpriseDiscountRate(identity);

        BigDecimal finalRate = modelRate.min(userRate)
                .multiply(enterpriseRate)
                .setScale(SCALE, RoundingMode.HALF_UP);

        return new DiscountBreakdown(modelRate, userRate, enterpriseRate, finalRate);
    }

    private ModelPricingService.ModelPricing tryGetPricing(String modelName, String channelId) {
        try {
            return modelPricingService.getPrice(modelName, channelId);
        } catch (Exception e) {
            LOGGER.warn("查询模型定价失败, modelName={}, channelId={}: {}", modelName, channelId, e.getMessage());
            return null;
        }
    }

    private BigDecimal resolveModelDiscountRate(ModelPricingService.ModelPricing pricing) {
        if (pricing == null || pricing.getDiscountRate() == null) {
            return NO_DISCOUNT;
        }
        return normalizeRate(pricing.getDiscountRate());
    }

    private BigDecimal resolveUserDiscountRate(UserIdentity identity, Long modelId) {
        if (identity == null || !identity.platformUser()) {
            return NO_DISCOUNT;
        }
        if (modelId == null) {
            return NO_DISCOUNT;
        }
        Integer userType = identity.userType();
        if (userType == null) {
            return NO_DISCOUNT;
        }
        String userId;
        if (userType == 1) {
            userId = identity.companyId();
        } else if (userType == 2) {
            userId = identity.systemUserId() != null ? String.valueOf(identity.systemUserId()) : null;
        } else {
            LOGGER.warn("未知用户类型, 按无折扣处理: userType={}", userType);
            return NO_DISCOUNT;
        }
        if (userId == null || userId.isBlank()) {
            return NO_DISCOUNT;
        }
        try {
            Optional<UserModelDiscountEntity> opt = userModelDiscountRepository
                    .findFirstByUserTypeAndUserIdAndModelIdAndDeletedFalse(userType, userId, modelId);
            if (opt.isEmpty() || opt.get().getDiscount() == null) {
                return NO_DISCOUNT;
            }
            return percentageToRate(opt.get().getDiscount());
        } catch (Exception e) {
            LOGGER.warn("查询用户模型折扣失败, userType={}, userId={}, modelId={}: {}",
                    userType, userId, modelId, e.getMessage());
            return NO_DISCOUNT;
        }
    }

    private BigDecimal resolveEnterpriseDiscountRate(UserIdentity identity) {
        if (identity == null || !identity.platformUser()) {
            return NO_DISCOUNT;
        }
        if (!Integer.valueOf(1).equals(identity.userType()) || identity.enterpriseId() == null) {
            return NO_DISCOUNT;
        }
        try {
            Optional<PlatformEnterpriseEntity> opt = enterpriseRepository.findById(identity.enterpriseId());
            if (opt.isEmpty() || opt.get().getSubsidyDiscount() == null) {
                return NO_DISCOUNT;
            }
            return percentageToRate(opt.get().getSubsidyDiscount());
        } catch (Exception e) {
            LOGGER.warn("查询企业补贴折扣失败, enterpriseId={}: {}", identity.enterpriseId(), e.getMessage());
            return NO_DISCOUNT;
        }
    }

    private BigDecimal percentageToRate(Integer percentage) {
        if (percentage == null || percentage < 0) {
            return NO_DISCOUNT;
        }
        return new BigDecimal(percentage).divide(new BigDecimal("100"), SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeRate(BigDecimal rate) {
        if (rate == null || rate.compareTo(BigDecimal.ZERO) < 0) {
            return NO_DISCOUNT;
        }
        return rate.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
