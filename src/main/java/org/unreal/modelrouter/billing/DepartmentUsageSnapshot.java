package org.unreal.modelrouter.billing;

import java.io.Serializable;
import java.time.LocalDate;

/** Immutable admission-time attribution, also persisted for delayed video settlement. */
public record DepartmentUsageSnapshot(String requestId, String departmentId, String departmentName,
        String fundingSource, boolean freeUsage, String policySource, long policyVersion,
        String accountId, Integer accountType, LocalDate periodStart, Long departmentTokenLimit,
        java.math.BigDecimal memberCostLimit, Long memberTokenLimit, String policyUpdatedAt) implements Serializable {
    public boolean departmentFunded() { return "DEPARTMENT".equals(fundingSource); }
}
