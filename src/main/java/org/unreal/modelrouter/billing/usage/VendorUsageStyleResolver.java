package org.unreal.modelrouter.billing.usage;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 厂商 usage 风格识别器：vendor 字段 + baseUrl 正则双重识别。
 *
 * <p>优先用 ai_model.vendor 字段精确匹配；未命中时用 baseUrl 正则兜底识别；
 * 仍无法识别时默认 OPENAI 风格（最通用，prompt_tokens 含缓存命中）。
 */
@Component
public class VendorUsageStyleResolver {

    /** vendor -> UsageStyle 映射。国产厂商大多兼容 DeepSeek/OpenAI 格式（prompt_tokens 含缓存命中）。 */
    // 注意：Map.of 最多 10 对，这里 11 对必须用 Map.ofEntries
    private static final Map<String, UsageStyle> VENDOR_MAP = Map.ofEntries(
            Map.entry("deepseek", UsageStyle.DEEPSEEK),
            Map.entry("glm", UsageStyle.DEEPSEEK),
            Map.entry("zhipu", UsageStyle.DEEPSEEK),
            Map.entry("kimi", UsageStyle.DEEPSEEK),
            Map.entry("moonshot", UsageStyle.DEEPSEEK),
            Map.entry("tianyi", UsageStyle.DEEPSEEK),
            Map.entry("volcengine", UsageStyle.DEEPSEEK),
            Map.entry("ark", UsageStyle.DEEPSEEK),
            Map.entry("openai", UsageStyle.OPENAI),
            Map.entry("anthropic", UsageStyle.ANTHROPIC),
            Map.entry("claude", UsageStyle.ANTHROPIC)
    );

    /** DeepSeek 风格厂商的 baseUrl 正则（兜底识别） */
    private static final List<Pattern> DEEPSEEK_URL_PATTERNS = List.of(
            Pattern.compile(".*deepseek\\.com.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*bigmodel\\.cn.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*moonshot\\.cn.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*ctyun.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*volces\\.com.*", Pattern.CASE_INSENSITIVE)
    );

    /** Anthropic 风格厂商的 baseUrl 正则 */
    private static final List<Pattern> ANTHROPIC_URL_PATTERNS = List.of(
            Pattern.compile(".*anthropic\\.com.*", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 解析厂商 usage 风格。
     *
     * @param vendor  ai_model.vendor 字段（可为 null）
     * @param baseUrl 实例 baseUrl（可为 null）
     * @return 识别到的 UsageStyle，无法识别时返回 {@link UsageStyle#OPENAI}
     */
    public UsageStyle resolve(final String vendor, final String baseUrl) {
        // 1. 优先 vendor 字段精确匹配
        if (vendor != null && !vendor.isBlank()) {
            final UsageStyle style = VENDOR_MAP.get(vendor.toLowerCase().trim());
            if (style != null) {
                return style;
            }
        }
        // 2. 兜底 baseUrl 正则匹配
        if (baseUrl != null && !baseUrl.isBlank()) {
            for (final Pattern p : ANTHROPIC_URL_PATTERNS) {
                if (p.matcher(baseUrl).find()) {
                    return UsageStyle.ANTHROPIC;
                }
            }
            for (final Pattern p : DEEPSEEK_URL_PATTERNS) {
                if (p.matcher(baseUrl).find()) {
                    return UsageStyle.DEEPSEEK;
                }
            }
        }
        // 3. 默认 OpenAI 风格（最通用，prompt_tokens 含缓存命中）
        return UsageStyle.OPENAI;
    }
}
