package org.unreal.modelrouter.billing.rule;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.persistence.jpa.entity.BillingDimensionEntity;
import org.unreal.modelrouter.persistence.jpa.repository.BillingDimensionRepository;
import org.unreal.modelrouter.persistence.jpa.repository.PricingRuleRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.unreal.modelrouter.billing.rule.RuleAdminDtos.*;

/**
 * 计费维度管理服务（数据驱动维度 CRUD）。
 *
 * <p>value_type：price（单价）/ flag（开关）/ discount（折扣 0-100）。
 * 已被任意规则 price_json 引用的维度禁止删除（JSONB 引用检查）。
 */
@Service
@RequiredArgsConstructor
public class DimensionService {

    private static final Set<String> GROUP_KEYS = Set.of("text", "voice", "image");
    private static final Set<String> VALUE_TYPES = Set.of("price", "flag", "discount");

    private final BillingDimensionRepository dimensionRepository;
    private final PricingRuleRepository ruleRepository;

    public List<DimensionDto> list(String groupKey) {
        List<BillingDimensionEntity> entities = (groupKey == null || groupKey.isBlank())
                ? dimensionRepository.findAll()
                : dimensionRepository.findByGroupKeyOrderBySortOrderAsc(groupKey);
        return entities.stream().map(this::toDto).toList();
    }

    @Transactional
    public DimensionDto create(DimensionCreateDto dto) {
        if (dto.groupKey() == null || !GROUP_KEYS.contains(dto.groupKey())) {
            throw bad("无效的计费组: " + dto.groupKey() + "（仅支持 text/voice/image）");
        }
        if (dto.dimensionKey() == null || dto.dimensionKey().isBlank()) {
            throw bad("维度键必填");
        }
        String key = dto.dimensionKey().trim();
        if (key.length() > 32) throw bad("维度键过长（≤32）");
        if (dto.valueType() == null || !VALUE_TYPES.contains(dto.valueType())) {
            throw bad("无效的维度类型: " + dto.valueType() + "（仅支持 price/flag/discount）");
        }
        if (dimensionRepository.findByGroupKeyAndDimensionKey(dto.groupKey(), key).isPresent()) {
            throw bad("该组下维度已存在: " + key);
        }
        BillingDimensionEntity e = new BillingDimensionEntity();
        e.setGroupKey(dto.groupKey());
        e.setDimensionKey(key);
        e.setDisplayName(dto.displayName() == null || dto.displayName().isBlank() ? key : dto.displayName().trim());
        e.setUnit(dto.unit() == null || dto.unit().isBlank() ? "元 / 百万 Token" : dto.unit().trim());
        e.setValueType(dto.valueType());
        e.setSortOrder(dimensionRepository.findByGroupKeyOrderBySortOrderAsc(dto.groupKey()).size() + 1);
        e.setEnabled(true);
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        return toDto(dimensionRepository.save(e));
    }

    @Transactional
    public DimensionDto update(Long id, DimensionUpdateDto dto) {
        BillingDimensionEntity e = dimensionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "维度不存在: " + id));
        if (dto.displayName() != null) {
            String name = dto.displayName().trim();
            if (name.isEmpty()) throw bad("显示名不能为空");
            if (name.length() > 60) throw bad("显示名过长（≤60）");
            e.setDisplayName(name);
        }
        if (dto.unit() != null && !dto.unit().isBlank()) {
            e.setUnit(dto.unit().trim());
        }
        if (dto.sortOrder() != null) e.setSortOrder(dto.sortOrder());
        if (dto.enabled() != null) e.setEnabled(dto.enabled());
        e.setUpdatedAt(LocalDateTime.now());
        return toDto(dimensionRepository.save(e));
    }

    @Transactional
    public void delete(Long id) {
        BillingDimensionEntity e = dimensionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "维度不存在: " + id));
        long refCount = ruleRepository.countRulesReferencingDimension(e.getDimensionKey());
        if (refCount > 0) {
            throw bad("维度「" + e.getDisplayName() + "」已被 " + refCount + " 条计费规则引用，禁止删除");
        }
        dimensionRepository.delete(e);
    }

    private DimensionDto toDto(BillingDimensionEntity e) {
        return new DimensionDto(e.getId(), e.getGroupKey(), e.getDimensionKey(),
                e.getDisplayName(), e.getUnit(), e.getValueType(),
                e.getSortOrder() != null ? e.getSortOrder() : 0,
                Boolean.TRUE.equals(e.getEnabled()));
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}