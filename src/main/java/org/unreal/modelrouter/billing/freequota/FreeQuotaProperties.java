// 文件说明：FreeQuotaProperties：负责计费与余额管理中的组件实现。
package org.unreal.modelrouter.billing.freequota;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "jairouter.billing.free-quota")
public class FreeQuotaProperties {

    private boolean enabled = true;
    private long defaultTotal = 1_000_000L;
    private Set<String> serviceTypes = new HashSet<>(Set.of("chat", "embedding", "rerank"));
    private Map<String, Long> roleTotals = new LinkedHashMap<>(Map.of(
            "STUDENT", 1_000_000L,
            "STAFF", 3_000_000L,
            "TEACHER", 5_000_000L));
    private String defaultTier = "STUDENT";
    private String resetCron = "0 5 0 1 * *";
    private String timeZone = "Asia/Shanghai";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getDefaultTotal() { return defaultTotal; }
    public void setDefaultTotal(long defaultTotal) { this.defaultTotal = defaultTotal; }

    public Set<String> getServiceTypes() { return serviceTypes; }
    public void setServiceTypes(Set<String> serviceTypes) { this.serviceTypes = new HashSet<>(serviceTypes); }

    public Map<String, Long> getRoleTotals() { return roleTotals; }
    public void setRoleTotals(Map<String, Long> roleTotals) {
        this.roleTotals = new LinkedHashMap<>();
        roleTotals.forEach((role, total) -> this.roleTotals.put(role.toUpperCase(Locale.ROOT), total));
    }

    public String getDefaultTier() { return defaultTier; }
    public void setDefaultTier(String defaultTier) { this.defaultTier = defaultTier; }

    public String getResetCron() { return resetCron; }
    public void setResetCron(String resetCron) { this.resetCron = resetCron; }

    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }

    public boolean isFreeQuotaServiceType(String serviceType) {
        return serviceType != null && serviceTypes.contains(serviceType.toLowerCase());
    }

    public QuotaPolicy resolvePolicy(Collection<String> roles) {
        String selectedTier = defaultTier.toUpperCase(Locale.ROOT);
        long selectedTotal = roleTotals.getOrDefault(selectedTier, defaultTotal);
        if (roles != null) {
            for (String role : roles) {
                if (role == null) {
                    continue;
                }
                String normalized = role.toUpperCase(Locale.ROOT).replaceFirst("^ROLE_", "");
                Long candidate = roleTotals.get(normalized);
                if (candidate != null && candidate > selectedTotal) {
                    selectedTier = normalized;
                    selectedTotal = candidate;
                }
            }
        }
        return new QuotaPolicy(selectedTier, selectedTotal);
    }

    public record QuotaPolicy(String tier, long total) {
    }
}
