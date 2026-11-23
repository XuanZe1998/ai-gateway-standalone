package org.unreal.modelrouter.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Shared local asset catalog; only catalog IDs can be saved as presentation overrides. */
public final class ModelIconLibrary {
    private record Icon(String id, String label, String file, String modelPattern,
                        String brandPattern, String source) {}
    private record Catalog(int version, String deferredModelPattern, List<Icon> icons, Map<String, String> fallbacks) {}
    private record Rule(String id, Pattern model, Pattern brand) {}
    private static final Catalog CATALOG = load();
    private static final Pattern DEFERRED = Pattern.compile(CATALOG.deferredModelPattern(), Pattern.CASE_INSENSITIVE);
    private static final List<Rule> RULES = CATALOG.icons().stream()
            .filter(icon -> !icon.modelPattern().isBlank())
            .map(icon -> new Rule(icon.id(), Pattern.compile(icon.modelPattern(), Pattern.CASE_INSENSITIVE),
                    Pattern.compile(icon.brandPattern(), Pattern.CASE_INSENSITIVE))).toList();

    private ModelIconLibrary() {}

    private static Catalog load() {
        try (var input = new ClassPathResource("static/model-icons/catalog.json").getInputStream()) {
            return new ObjectMapper().readValue(input, Catalog.class);
        } catch (IOException error) {
            throw new IllegalStateException("模型图标库加载失败", error);
        }
    }

    public static boolean contains(String id) {
        return id != null && CATALOG.icons().stream().anyMatch(icon -> icon.id().equals(id));
    }

    public static String resolve(String serviceType, String modelId, String displayName, List<String> vendors) {
        // Prefer the actual model ID to a renamed title or an upstream routing provider.
        String normalized = modelId.trim().toLowerCase(Locale.ROOT);
        String shortId = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (DEFERRED.matcher(normalized).find() || DEFERRED.matcher(shortId).find()) {
            return CATALOG.fallbacks().getOrDefault(serviceType, CATALOG.fallbacks().get("unknown"));
        }
        for (var rule : RULES) {
            if (rule.model().matcher(normalized).find() || rule.model().matcher(shortId).find()) return rule.id();
        }
        for (var rule : RULES) {
            if (displayName != null && rule.brand().matcher(displayName).find()) return rule.id();
        }
        for (var vendor : vendors) {
            for (var rule : RULES) {
                if (vendor != null && rule.brand().matcher(vendor).find()) return rule.id();
            }
        }
        return CATALOG.fallbacks().getOrDefault(serviceType, CATALOG.fallbacks().get("unknown"));
    }
}
