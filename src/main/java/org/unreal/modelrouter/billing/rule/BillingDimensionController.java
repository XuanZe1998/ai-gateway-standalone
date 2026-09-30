package org.unreal.modelrouter.billing.rule;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.unreal.modelrouter.billing.rule.RuleAdminDtos.DimensionCreateDto;
import org.unreal.modelrouter.billing.rule.RuleAdminDtos.DimensionDto;
import org.unreal.modelrouter.billing.rule.RuleAdminDtos.DimensionUpdateDto;

import java.util.List;

/** 计费维度管理 API（billingMode=3 规则引擎）。 */
@RestController
@RequestMapping("/api/admin/billing/dimensions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class BillingDimensionController {

    private final DimensionService dimensionService;

    @GetMapping
    public List<DimensionDto> list(String groupKey) {
        return dimensionService.list(groupKey);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public DimensionDto create(@RequestBody DimensionCreateDto dto) {
        return dimensionService.create(dto);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public DimensionDto update(@PathVariable Long id, @RequestBody DimensionUpdateDto dto) {
        return dimensionService.update(id, dto);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        dimensionService.delete(id);
    }
}