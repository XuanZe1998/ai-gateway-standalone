package org.unreal.modelrouter.billing.usage;

/**
 * 厂商 usage 返回风格。
 *
 * <p>核心差异：rawPromptTokens（prompt_tokens / input_tokens）是否包含缓存命中 token。
 * <ul>
 *   <li>DEEPSEEK / OPENAI：prompt_tokens 是总量（含缓存命中），cache 是其子集，需扣除得净量</li>
 *   <li>ANTHROPIC：input_tokens 已是净量（不含 cache），cache 单独字段，不可重复扣</li>
 * </ul>
 */
public enum UsageStyle {
    /** DeepSeek 风格：prompt_tokens=hit+miss，cache=prompt_cache_hit_tokens，reasoning=completion_tokens_details.reasoning_tokens */
    DEEPSEEK,
    /** OpenAI 风格：prompt_tokens 含缓存命中，cache=prompt_tokens_details.cached_tokens，reasoning=completion_tokens_details.reasoning_tokens */
    OPENAI,
    /** Anthropic 风格：input_tokens 已是净量，cache 单独在 cache_creation/cache_read_input_tokens，无独立 reasoning 字段 */
    ANTHROPIC,
    /** 未知/默认：按 OpenAI 风格兜底处理（最通用，prompt_tokens 含缓存命中） */
    UNKNOWN
}
