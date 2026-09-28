package org.unreal.modelrouter.catalog;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.unreal.modelrouter.billing.DiscountBreakdown;

/** Explicit public allowlist. Never serialize platform entities or routing instances. */
public final class ModelSquareDtos {
    private ModelSquareDtos() {}
    public record PriceLine(String item, String unit, BigDecimal standardPrice,
                            BigDecimal effectivePrice, String status, String condition) {}
    public record Scheme(String label, String mode, boolean configured, DiscountBreakdown discounts,
                         List<PriceLine> lines, List<String> notes) {}
    public record Card(String serviceType, String modelId, String displayName, String description,
                       List<String> tags, List<String> vendors, int schemeCount, String priceStatus,
                       List<PriceLine> priceSummary, boolean freeQuotaApplicable, boolean overridden) {}
    public record Access(String method, String path, String contentType, String queryPath, String note) {}
    public record Detail(Card model, List<Scheme> schemes, Access access, List<String> notes) {}
    public record Content(String serviceType, String modelId, String displayName, String description,
                          List<String> tags) {}
    public record ContentView(Card model, String defaultName, String defaultDescription,
                              List<String> defaultTags, Content override, LocalDateTime updatedAt,
                              String updatedBy) {}
}
