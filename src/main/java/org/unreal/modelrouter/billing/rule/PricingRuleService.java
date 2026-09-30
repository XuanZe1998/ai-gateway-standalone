package org.unreal.modelrouter.billing.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.billing.ModelPricingService;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformModelEntity;
import org.unreal.modelrouter.persistence.jpa.entity.PricingRuleEntity;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformModelRepository;
import org.unreal.modelrouter.persistence.jpa.repository.PricingRuleRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.unreal.modelrouter.billing.rule.RuleAdminDtos.*;

/**
 * 计费规则管理服务（billingMode=3）。
 *
 * <p>规则保存时自动确保 ai_model 行存在（缺失则建空壳）并把 billing_mode 置为 3（规则计费），
 * 保存后立即刷新定价缓存即时生效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingRuleService {

    private final PricingRuleRepository ruleRepository;
    private final PlatformModelRepository modelRepository;
    private final ModelPricingService pricingService;
    private final ObjectMapper objectMapper;

    public List<RuleDto> list(String modelName) {
        PlatformModelEntity m = findModel(modelName);
        if (m == null || m.getId() == null) return List.of();
        return ruleRepository.findByModelIdOrderByPriorityAsc(m.getId()).stream()
                .map(r -> toDto(r, modelName))
                .toList();
    }

    @Transactional
    public RuleDto create(RuleCreateDto dto) {
        if (dto.modelName() == null || dto.modelName().isBlank()) throw bad("模型标识必填");
        if (dto.ruleName() == null || dto.ruleName().isBlank()) throw bad("规则名称必填");
        validateJson(dto.matchJson(), dto.priceJson());

        PlatformModelEntity m = ensureModelRow(dto.modelName());
        switchModelToBilling(m);

        // 优先级默认：取当前最大 + 10
        int priority = dto.priority() != null ? dto.priority()
                : ruleRepository.findByModelIdOrderByPriorityAsc(m.getId()).stream()
                        .mapToInt(r -> r.getPriority() != null ? r.getPriority() : 0)
                        .max().orElse(0) + 10;

        PricingRuleEntity e = new PricingRuleEntity();
        e.setModelId(m.getId());
        e.setRuleName(dto.ruleName().trim());
        e.setMatchJson(dto.matchJson());
        e.setPriceJson(dto.priceJson());
        e.setPriority(priority);
        e.setEnabled(true);
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        ruleRepository.save(e);
        pricingService.refreshPricing();
        return toDto(e, dto.modelName());
    }

    @Transactional
    public RuleDto update(Long id, RuleUpdateDto dto) {
        PricingRuleEntity e = ruleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "规则不存在: " + id));
        PlatformModelEntity m = modelRepository.findById(e.getModelId()).orElse(null);
        String modelName = m != null ? m.getRealName() : String.valueOf(e.getModelId());
        if (dto.ruleName() != null) {
            if (dto.ruleName().isBlank()) throw bad("规则名称不能为空");
            e.setRuleName(dto.ruleName().trim());
        }
        if (dto.matchJson() != null || dto.priceJson() != null) {
            String match = dto.matchJson() != null ? dto.matchJson() : e.getMatchJson();
            String price = dto.priceJson() != null ? dto.priceJson() : e.getPriceJson();
            validateJson(match, price);
            if (dto.matchJson() != null) e.setMatchJson(dto.matchJson());
            if (dto.priceJson() != null) e.setPriceJson(dto.priceJson());
        }
        if (dto.enabled() != null) e.setEnabled(dto.enabled());
        e.setUpdatedAt(LocalDateTime.now());
        ruleRepository.save(e);
        pricingService.refreshPricing();
        return toDto(e, modelName);
    }

    @Transactional
    public void delete(Long id) {
        PricingRuleEntity e = ruleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "规则不存在: " + id));
        ruleRepository.delete(e);
        pricingService.refreshPricing();
    }

    @Transactional
    public void updatePriority(Long id, PriorityDto dto) {
        PricingRuleEntity e = ruleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "规则不存在: " + id));
        Long modelId = e.getModelId();
        // 校验新优先级不与其他规则冲突
        boolean conflict = ruleRepository.findByModelIdOrderByPriorityAsc(modelId).stream()
                .anyMatch(r -> !r.getId().equals(id) && r.getPriority() != null && r.getPriority() == dto.priority());
        if (conflict) throw bad("优先级 " + dto.priority() + " 已被其他规则占用");
        e.setPriority(dto.priority());
        e.setUpdatedAt(LocalDateTime.now());
        ruleRepository.save(e);
        pricingService.refreshPricing();
    }

    /** 拖拽排序：按传入顺序整体重排优先级（10, 20, 30, ...） */
    @Transactional
    public void reorder(List<Long> orderedRuleIds) {
        if (orderedRuleIds == null || orderedRuleIds.isEmpty()) return;
        List<PricingRuleEntity> entities = new ArrayList<>();
        int idx = 0;
        for (Long ruleId : orderedRuleIds) {
            PricingRuleEntity e = ruleRepository.findById(ruleId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "规则不存在: " + ruleId));
            e.setPriority((idx + 1) * 10);
            e.setUpdatedAt(LocalDateTime.now());
            entities.add(e);
            idx++;
        }
        ruleRepository.saveAll(entities);
        pricingService.refreshPricing();
    }

    // ===== 内部 =====

    /** 查询模型行（可能为空） */
    private PlatformModelEntity findModel(String modelName) {
        if (modelName == null) return null;
        return modelRepository.findByRealNameAndDeletedFalse(modelName.trim()).orElse(null);
    }

    /** 模型行不存在则创建空壳（id/realName/status=1/deleted=false/modelType） */
    private PlatformModelEntity ensureModelRow(String modelName) {
        PlatformModelEntity m = findModel(modelName);
        if (m != null) return m;
        m = new PlatformModelEntity();
        m.setId(modelRepository.findMaxId() + 1);
        m.setRealName(modelName.trim());
        m.setStatus("1");
        m.setDeleted(false);
        m.setCreateTime(LocalDateTime.now());
        m.setModelType("1");
        m.setBillingMode(3);
        return modelRepository.saveAndFlush(m);
    }

    /** 确保模型处于规则计费模式（billingMode=3）并刷新 */
    private void switchModelToBilling(PlatformModelEntity m) {
        if (!Integer.valueOf(3).equals(m.getBillingMode())) {
            m.setBillingMode(3);
            m.setUpdateTime(LocalDateTime.now());
            modelRepository.saveAndFlush(m);
        }
    }

    private void validateJson(String matchJson, String priceJson) {
        if (matchJson == null || matchJson.isBlank()) throw bad("匹配条件（match_json）必填");
        if (priceJson == null || priceJson.isBlank()) throw bad("价格（price_json）必填");
        try {
            JsonNode match = objectMapper.readTree(matchJson);
            if (!match.isObject()) throw bad("match_json 必须是 JSON 对象");
            // 无条件规则：{"operator":"AND","conditions":[]}；有条件规则必须含 operator/conditions 或 field
            String operator = match.path("operator").asText(null);
            if (operator == null && !match.has("field")) {
                throw bad("match_json 缺少 operator 或 field 字段");
            }
            JsonNode price = objectMapper.readTree(priceJson);
            if (!price.isObject()) throw bad("price_json 必须是 JSON 对象");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw bad("JSON 解析失败: " + e.getMessage());
        }
    }

    private RuleDto toDto(PricingRuleEntity e, String modelName) {
        return new RuleDto(e.getId(), e.getModelId(), modelName, e.getRuleName(),
                e.getMatchJson(), e.getPriceJson(),
                e.getPriority() != null ? e.getPriority() : 0,
                Boolean.TRUE.equals(e.getEnabled()));
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}