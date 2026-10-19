package org.unreal.modelrouter.billing.pricing;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.persistence.jpa.entity.PricingAdjustmentEntity;
import org.unreal.modelrouter.persistence.jpa.repository.PricingAdjustmentRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformModelRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Quoted unit prices are adjusted once: model override (including legacy discount) > global. */
@Service
@RequiredArgsConstructor
public class PricingAdjustmentService {
    public record Adjustment(boolean enabled, String mode, BigDecimal value) {
        public BigDecimal rate() {
            return enabled && "PERCENTAGE".equals(mode) ? value.movePointLeft(2) : BigDecimal.ONE;
        }
        public BigDecimal fixedAmount() {
            return enabled && "FIXED".equals(mode) ? value : BigDecimal.ZERO;
        }
    }
    private static final Adjustment DEFAULT = new Adjustment(false, "PERCENTAGE", new BigDecimal("100"));
    private final PricingAdjustmentRepository repository;
    private final PlatformModelRepository modelRepository;

    public Adjustment global() { return repository.findById("GLOBAL").map(this::view).orElse(DEFAULT); }

    public Adjustment model(String modelName) {
        var explicit = repository.findById(modelKey(modelName));
        if (explicit.isPresent()) return view(explicit.get());
        return modelRepository.findByRealNameAndDeletedFalse(modelName.trim())
                .filter(m -> m.getDiscount() != null)
                .map(m -> new Adjustment(true, "PERCENTAGE", BigDecimal.valueOf(m.getDiscount())))
                .orElse(DEFAULT);
    }

    public Adjustment resolve(String modelName) {
        Adjustment specific = model(modelName);
        return specific.enabled() ? specific : global();
    }

    @Transactional
    public Adjustment save(String modelName, Adjustment request) {
        if (request == null || !java.util.Set.of("PERCENTAGE", "FIXED").contains(request.mode() == null ? "" : request.mode()))
            throw bad("请选择百分比折扣或固定金额增减");
        BigDecimal value = request.value();
        if (value == null || value.stripTrailingZeros().scale() > 8 || value.abs().compareTo(new BigDecimal("1000000000000")) >= 0)
            throw bad("调价值必填，最多保留八位小数，绝对值须小于一万亿元");
        if ("PERCENTAGE".equals(request.mode()) && (value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0))
            throw bad("折扣范围为 0-100，100 表示原价");
        String key = modelName == null ? "GLOBAL" : modelKey(modelName);
        PricingAdjustmentEntity entity = repository.findById(key).orElseGet(PricingAdjustmentEntity::new);
        entity.setScopeKey(key);
        entity.setEnabled(request.enabled());
        entity.setMode(request.mode());
        entity.setValue(value.setScale(8, RoundingMode.HALF_UP));
        return view(repository.saveAndFlush(entity));
    }

    private String modelKey(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 255) throw bad("模型标识必填且最多255个字符");
        return "MODEL:" + name.trim().toLowerCase(Locale.ROOT);
    }
    private Adjustment view(PricingAdjustmentEntity e) { return new Adjustment(Boolean.TRUE.equals(e.getEnabled()), e.getMode(), e.getValue()); }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
