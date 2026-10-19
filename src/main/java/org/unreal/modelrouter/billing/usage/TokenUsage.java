package org.unreal.modelrouter.billing.usage;

/**
 * 归一化 Token 用量；文字和音频输入、缓存为互斥的净量。
 *
 * <p>归一化规则：无论上游厂商返回的"输入token"语义如何（DeepSeek/OpenAI 含缓存命中，
 * Anthropic 不含），归一化后 {@link #normalInput()} 始终是"缓存未命中的净输入量"。
 *
 * <p>下游读取互斥用量，旧调用方仍可使用六维构造方法。
 */
public record TokenUsage(
        // ===== 文字及缓存计费 token（归一化后，不含独立音频维度）=====
        long normalInput,          // 非音频输入（缓存未命中净量）
        long cacheHit,             // 非音频隐式缓存命中
        long cacheCreateExplicit,  // 显式缓存创建（Anthropic cache_creation_input_tokens）
        long cacheHitExplicit,     // 显式缓存命中（Anthropic cache_read_input_tokens）
        long normalOutput,         // 普通输出 = 输出token − 思考token
        long thinking,             // 思考 token（reasoning_tokens）

        // ===== 原始总量（用于对账/日志，不直接参与计费）=====
        long rawPromptTokens,      // 厂商返回的原始输入总量（DeepSeek/OpenAI=含缓存, Anthropic=净量）
        long rawCompletionTokens,  // 厂商返回的原始输出总量（含思考）
        long rawTotalTokens,       // 厂商返回的 total_tokens
        long audioInput,           // 音频未命中缓存输入
        long audioCacheHit,        // 音频缓存命中输入
        boolean audioDetailsPresent,
        boolean audioUsageValid
) {
    /** 保留原六维调用方；没有音频明细时不推测音频用量。 */
    public TokenUsage(long normalInput, long cacheHit, long cacheCreateExplicit, long cacheHitExplicit,
                      long normalOutput, long thinking, long rawPromptTokens, long rawCompletionTokens,
                      long rawTotalTokens) {
        this(normalInput, cacheHit, cacheCreateExplicit, cacheHitExplicit, normalOutput, thinking,
                rawPromptTokens, rawCompletionTokens, rawTotalTokens, 0, 0, false, true);
    }
    /** 空对象（usage 缺失/异常时兜底，全 0） */
    public static TokenUsage empty() {
        return new TokenUsage(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** 全部文字、音频及缓存 token 总量（用于免费额度扣减口径） */
    public long billableTotal() {
        return legacyPromptTokens() + legacyCompletionTokens();
    }

    /**
     * 向后兼容旧 promptTokens 语义：归一化后的"输入总量" = 全部输入 token。
     * <p>用于：旧 billing_record.prompt_tokens 列填充、阶梯计费上下文匹配。
     * <p>跨厂商统一——DeepSeek/OpenAI 的 prompt_tokens(含cache) 与
     * Anthropic 的 input_tokens(不含cache)+cache 字段，归一化后此值都是"全部输入"。
     */
    public long legacyPromptTokens() {
        return normalInput + cacheHit + cacheCreateExplicit + cacheHitExplicit + audioInput + audioCacheHit;
    }

    /** 向后兼容旧 completionTokens 语义：归一化后的"输出总量" = 普通输出 + 思考 */
    public long legacyCompletionTokens() {
        return normalOutput + thinking;
    }
}
