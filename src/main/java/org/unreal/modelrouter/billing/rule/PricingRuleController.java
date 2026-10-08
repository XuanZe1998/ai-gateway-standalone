package org.unreal.modelrouter.billing.rule;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.unreal.modelrouter.billing.rule.RuleAdminDtos.*;

import java.util.List;

/** 计费规则管理 API（billingMode=3 规则引擎）。 */
@RestController
@RequestMapping("/api/admin/pricing/rules")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PricingRuleController {

    private final PricingRuleService ruleService;

    @GetMapping
    public List<RuleDto> list(@RequestParam String modelName) {
        return ruleService.list(modelName);
    }

    @PostMapping
    public RuleDto create(@RequestBody RuleCreateDto dto) {
        return ruleService.create(dto);
    }

    @PutMapping("/{id}")
    public RuleDto update(@PathVariable Long id, @RequestBody RuleUpdateDto dto) {
        return ruleService.update(id, dto);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        ruleService.delete(id);
    }

    @PutMapping("/{id}/priority")
    public void updatePriority(@PathVariable Long id, @RequestBody PriorityDto dto) {
        ruleService.updatePriority(id, dto);
    }

    @PostMapping("/reorder")
    public void reorder(@RequestBody ReorderDto dto) {
        ruleService.reorder(dto.orderedRuleIds());
    }
}