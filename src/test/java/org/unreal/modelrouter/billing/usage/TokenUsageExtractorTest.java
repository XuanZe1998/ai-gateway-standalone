package org.unreal.modelrouter.billing.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TokenUsageExtractor} 单元测试（6 维 usage 归一化）。
 *
 * 该类承接了历史上散落在 ProtocolPassthroughService / BaseAdapter /
 * StreamingRequestProcessor 三处的 usage 提取逻辑（OpenAI / Anthropic 字段命名
 * 兼容、缺失字段归 0、非法输入返回 {@link TokenUsage#empty()}），
 * 测试目标随重构迁移至此。
 */
class TokenUsageExtractorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TokenUsageExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new TokenUsageExtractor(new VendorUsageStyleResolver(), objectMapper);
    }

    // ==================== 非流式 usage 提取 ====================

    @Test
    void extract_openAiFormat_normalizesLegacyFields() throws Exception {
        JsonNode usage = objectMapper.readTree(
                "{\"prompt_tokens\":15,\"completion_tokens\":42,\"total_tokens\":57}");

        TokenUsage result = extractor.extract(usage, null, null);

        // 无缓存/思考字段时，归一化后与原始字段一致
        assertThat(result.legacyPromptTokens()).isEqualTo(15L);
        assertThat(result.legacyCompletionTokens()).isEqualTo(42L);
        assertThat(result.rawTotalTokens()).isEqualTo(57L);
    }

    @Test
    void extract_anthropicFormat_inputOutputTokens() throws Exception {
        JsonNode usage = objectMapper.readTree(
                "{\"input_tokens\":25,\"output_tokens\":42}");

        TokenUsage result = extractor.extract(usage, "anthropic", null);

        assertThat(result.legacyPromptTokens()).isEqualTo(25L);
        assertThat(result.legacyCompletionTokens()).isEqualTo(42L);
        // Anthropic 无 total_tokens：兜底 = input + output = 67
        assertThat(result.rawTotalTokens()).isEqualTo(67L);
    }

    @Test
    void extract_missingUsageFields_returnsEmptyBillable() {
        TokenUsage result = extractor.extract(objectMapper.createObjectNode(), null, null);

        assertThat(result.billableTotal()).isZero();
    }

    // ==================== 流式 chunk usage 提取 ====================

    @Test
    void extractFromChunk_openAiUsageChunk() {
        String chunk = "{\"id\":\"chatcmpl-1\",\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":86,\"total_tokens\":98}}";

        TokenUsage result = extractor.extractFromChunk(chunk, null, null);

        assertThat(result.legacyPromptTokens()).isEqualTo(12L);
        assertThat(result.legacyCompletionTokens()).isEqualTo(86L);
        assertThat(result.rawTotalTokens()).isEqualTo(98L);
    }

    @Test
    void extractFromChunk_doneMarker_returnsEmpty() {
        TokenUsage result = extractor.extractFromChunk("[DONE]", null, null);

        assertThat(result.billableTotal()).isZero();
    }

    @Test
    void extractFromChunk_contentChunkWithoutUsage_returnsEmpty() {
        String chunk = "{\"id\":\"c\",\"choices\":[{\"delta\":{\"content\":\"春\"}}]}";

        TokenUsage result = extractor.extractFromChunk(chunk, null, null);

        assertThat(result.billableTotal()).isZero();
    }

    @Test
    void extractFromChunk_invalidJson_returnsEmpty() {
        TokenUsage result = extractor.extractFromChunk("not-json", null, null);

        assertThat(result.billableTotal()).isZero();
    }
}
