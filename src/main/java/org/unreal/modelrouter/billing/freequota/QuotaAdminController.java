package org.unreal.modelrouter.billing.freequota;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.common.controller.response.RouterResponse;

import java.util.List;

/** Administrative endpoints for idempotent campus quota provisioning. */
@RestController
@RequestMapping("/api/admin/quotas")
public class QuotaAdminController {

    private final FreeQuotaService quotaService;
    private final FreeQuotaRepository quotaRepository;

    public QuotaAdminController(final FreeQuotaService quotaService,
                                final FreeQuotaRepository quotaRepository) {
        this.quotaService = quotaService;
        this.quotaRepository = quotaRepository;
    }

    @PostMapping("/provision")
    public ResponseEntity<RouterResponse<QuotaView>> provision(
            @Valid @RequestBody final ProvisionRequest request) {
        FreeQuotaEntity quota = quotaService.ensureQuota(request.userId(), request.roles());
        return ResponseEntity.ok(RouterResponse.success(QuotaView.from(quota), "额度初始化完成"));
    }

    @PostMapping("/provision-batch")
    public ResponseEntity<RouterResponse<List<QuotaView>>> provisionBatch(
            @Valid @RequestBody final List<@Valid ProvisionRequest> requests) {
        if (requests.size() > 1000) {
            throw new IllegalArgumentException("单批最多初始化 1000 个用户");
        }
        List<QuotaView> result = requests.stream()
                .map(request -> quotaService.ensureQuota(request.userId(), request.roles()))
                .map(QuotaView::from)
                .toList();
        return ResponseEntity.ok(RouterResponse.success(result, "批量额度初始化完成"));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<RouterResponse<QuotaView>> getQuota(@PathVariable final String userId) {
        FreeQuotaEntity quota = quotaRepository.findByUserIdAndDeletedFalse(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户额度不存在: " + userId));
        return ResponseEntity.ok(RouterResponse.success(QuotaView.from(quota)));
    }

    public record ProvisionRequest(
            @NotBlank String userId,
            @NotEmpty List<@NotBlank String> roles
    ) {
    }

    public record QuotaView(
            String userId,
            String tier,
            long total,
            long used,
            long remaining,
            java.time.LocalDateTime periodStart,
            java.time.LocalDateTime periodEnd
    ) {
        private static QuotaView from(final FreeQuotaEntity entity) {
            return new QuotaView(
                    entity.getUserId(), entity.getQuotaTier(), entity.getTotalQuota(),
                    entity.getUsedQuota(), entity.getRemainingQuota(),
                    entity.getPeriodStart(), entity.getPeriodEnd());
        }
    }
}
