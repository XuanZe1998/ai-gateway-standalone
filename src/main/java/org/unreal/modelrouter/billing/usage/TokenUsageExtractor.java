package org.unreal.modelrouter.billing.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 统一 usage 提取 + 归一化入口。
 *
 * <p>替代历史散落在 BaseAdapter / StreamingRequestProcessor / ProtocolPassthroughService
 * 三处的 long[3] 重复提取逻辑。核心职责：
 * <ol>
 *   <li>从 OpenAI 兼容 usage 节点提取原始字段</li>
 *   <li>按 {@link UsageStyle} 归一化为 6 维净量 {@link TokenUsage}</li>
 * </ol>
 *
 * <p>归一化后 normalInput 始终是"缓存未命中净量"，消除厂商语义差异导致的资损：
 * <ul>
 *   <li>DeepSeek/OpenAI：prompt_tokens 含缓存命中 → normalInput = prompt_tokens − cacheHit</li>
 *   <li>Anthropic：input_tokens 已是净量 → normalInput = input_tokens（不重复扣 cache）</li>
 * </ul>
 *
 * <p>资损防护：归一化出现负值时 max0 兜底 + 告警日志；字段缺失统一置 0。
 */
@Component
public class TokenUsageExtractor {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageExtractor.class);

    private final VendorUsageStyleResolver styleResolver;
    private final ObjectMapper objectMapper;

    public TokenUsageExtractor(final VendorUsageStyleResolver styleResolver,
                               final ObjectMapper objectMapper) {
        this.styleResolver = styleResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * 从 OpenAI 兼容 usage 节点提取并归一化。
     *
     * @param usage   usage JsonNode（可为 null/missing）
     * @param vendor  ai_model.vendor（可为 null）
     * @param baseUrl 实例 baseUrl（可为 null）
     * @return 归一化后的 6 维 TokenUsage；usage 为空时返回 {@link TokenUsage#empty()}
     */
    public TokenUsage extract(final JsonNode usage, final String vendor, final String baseUrl) {
        if (usage == null || usage.isMissingNode() || !usage.isObject()) {
            return TokenUsage.empty();
        }
        final UsageStyle style = styleResolver.resolve(vendor, baseUrl);
        return doExtract(usage, style);
    }

    /**
     * 从 SSE chunk JSON 字符串提取并归一化（流式专用）。
     *
     * @param chunkJson SSE data 行的 JSON 字符串
     * @param vendor    ai_model.vendor
     * @param baseUrl   实例 baseUrl
     * @return 归一化后的 TokenUsage；解析失败返回 {@link TokenUsage#empty()}
     */
    public TokenUsage extractFromChunk(final String chunkJson, final String vendor, final String baseUrl) {
        try {
            final JsonNode node = objectMapper.readTree(chunkJson);
            return extract(node.path("usage"), vendor, baseUrl);
        } catch (final Exception e) {
            log.debug("解析流式 chunk usage 失败，兜底返回 empty: {}", e.getMessage());
            return TokenUsage.empty();
        }
    }

    private TokenUsage doExtract(final JsonNode usage, final UsageStyle style) {
        // 原始总量字段（兼容 OpenAI/Anthropic 命名）
        final long rawPrompt = firstNonZero(usage, "prompt_tokens", "input_tokens");
        final long rawCompletion = firstNonZero(usage, "completion_tokens", "output_tokens");
        // rawTotal 兜底：Anthropic 无 total_tokens 字段，且其 input_tokens 是净量（不含 cache），
        // 故 Anthropic 兜底要补上 cache_creation/cache_read，否则 total 偏小导致对账/免费额度口径失真
        final long rawTotalFallback = style == UsageStyle.ANTHROPIC
                ? rawPrompt + rawCompletion
                        + usage.path("cache_creation_input_tokens").asLong(0)
                        + usage.path("cache_read_input_tokens").asLong(0)
                : rawPrompt + rawCompletion;
        final long rawTotal = usage.path("total_tokens").asLong(rawTotalFallback);

        // 缓存/思考字段提取（按风格）
        final long cacheHit;
        final long cacheCreateExplicit;
        final long cacheHitExplicit;
        final long thinking;
        switch (style) {
            case DEEPSEEK -> {
                cacheHit = usage.path("prompt_cache_hit_tokens").asLong(0);
                cacheCreateExplicit = 0;
                cacheHitExplicit = 0;
                thinking = extractThinking(usage);
            }
            case OPENAI -> {
                cacheHit = usage.path("prompt_tokens_details").path("cached_tokens").asLong(0);
                cacheCreateExplicit = 0;
                cacheHitExplicit = 0;
                thinking = extractThinking(usage);
            }
            case ANTHROPIC -> {
                // Anthropic: input_tokens 已是净量（不含 cache），cache 单独字段
                cacheHit = 0;
                cacheCreateExplicit = usage.path("cache_creation_input_tokens").asLong(0);
                cacheHitExplicit = usage.path("cache_read_input_tokens").asLong(0);
                thinking = 0; // Anthropic 无独立 reasoning 字段，含在 output_tokens 内
            }
            default -> {
                // UNKNOWN -> 按 OPENAI 风格兜底，同时尝试 DeepSeek 字段名
                // 注意：asLong(default) 只在节点 missing 时用默认值，节点值为 0 时返回 0，
                //       故需显式判断 ==0 再取另一字段
                long ch = usage.path("prompt_tokens_details").path("cached_tokens").asLong(0);
                if (ch == 0) {
                    ch = usage.path("prompt_cache_hit_tokens").asLong(0);
                }
                cacheHit = ch;
                cacheCreateExplicit = 0;
                cacheHitExplicit = 0;
                thinking = extractThinking(usage);
            }
        }

        // ===== 归一化：确保 normalInput 始终是净量 =====
        final long normalInput;
        if (style == UsageStyle.ANTHROPIC) {
            // Anthropic: input_tokens 已是净量，直接用
            normalInput = rawPrompt;
        } else {
            // DeepSeek/OpenAI/UNKNOWN: rawPrompt 含缓存命中，需扣除
            normalInput = rawPrompt - cacheHit - cacheHitExplicit - cacheCreateExplicit;
        }
        // 普通输出 = 输出 − 思考
        final long normalOutput = rawCompletion - thinking;

        // 资损防护：负值保护 + 告警日志
        if (normalInput < 0 || normalOutput < 0) {
            log.warn("归一化出现负值, style={}, rawPrompt={}, cacheHit={}, cacheCreateExplicit={}, "
                            + "cacheHitExplicit={}, rawCompletion={}, thinking={}, normalInput={}, normalOutput={}",
                    style, rawPrompt, cacheHit, cacheCreateExplicit, cacheHitExplicit,
                    rawCompletion, thinking, normalInput, normalOutput);
        }

        return new TokenUsage(
                max0(normalInput), max0(cacheHit), max0(cacheCreateExplicit), max0(cacheHitExplicit),
                max0(normalOutput), max0(thinking),
                rawPrompt, rawCompletion, rawTotal
        );
    }

    /**
     * 提取思考 token：优先标准嵌套 {@code completion_tokens_details.reasoning_tokens}，
     * 为 0 时回退根级 {@code reasoning_tokens}。
     * 部分国产 OpenAI 兼容实现（如 kimi）把 reasoning_tokens 直接挂在 usage 根节点。
     */
    private static long extractThinking(final JsonNode usage) {
        final long nested = usage.path("completion_tokens_details").path("reasoning_tokens").asLong(0);
        if (nested != 0) {
            return nested;
        }
        return usage.path("reasoning_tokens").asLong(0);
    }

    private static long firstNonZero(final JsonNode usage, final String... keys) {
        for (final String k : keys) {
            final long v = usage.path(k).asLong(0);
            if (v != 0) {
                return v;
            }
        }
        return 0;
    }

    private static long max0(final long v) {
        return Math.max(0, v);
    }
}
