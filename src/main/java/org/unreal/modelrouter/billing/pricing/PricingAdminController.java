package org.unreal.modelrouter.billing.pricing;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.unreal.modelrouter.billing.pricing.PricingAdminDtos.*;

/** 模型定价管理接口（管理员）。Excel 批量导入接口后续版本增加。 */
@RestController
@RequestMapping("/api/admin/pricing")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class PricingAdminController {

    private final PricingAdminService service;

    @GetMapping
    public ResponseEntity<List<PricingRow>> list() {
        return ResponseEntity.ok(service.list());
    }

    @GetMapping("/detail")
    public ResponseEntity<PricingDetail> detail(@RequestParam String serviceType, @RequestParam String modelId) {
        return ResponseEntity.ok(service.detail(serviceType, modelId));
    }

    /** 保存主定价（手动修改） */
    @PutMapping
    public ResponseEntity<PricingRow> save(@RequestBody PricingSave body) {
        return ResponseEntity.ok(service.save(body));
    }

    /** 保存阶梯档位（整体替换） */
    @PutMapping("/tiers")
    public ResponseEntity<PricingRow> saveTiers(@RequestBody TiersSave body) {
        return ResponseEntity.ok(service.saveTiers(body));
    }

    /** 清除定价（恢复未配置） */
    @DeleteMapping
    public ResponseEntity<Boolean> delete(@RequestParam String serviceType, @RequestParam String modelId) {
        return ResponseEntity.ok(service.delete(serviceType, modelId));
    }
}

