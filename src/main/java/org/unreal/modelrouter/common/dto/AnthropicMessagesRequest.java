package org.unreal.modelrouter.common.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic 协议对话请求（/v1/messages）。
 *
 * 已知字段按接口文档声明为类型化字段（Swagger 自动生成完整 schema）；
 * 未知字段（如 thinking 及未来新增字段）通过 {@link JsonAnySetter} 兜底，
 * 序列化时经 {@link JsonAnyGetter} 原样平铺回顶层，保证协议转换不丢字段。
 */
@Data
@Schema(description = "Anthropic 协议对话请求")
public class AnthropicMessagesRequest {

    @Schema(description = "模型名称，如 claude-sonnet-4-20250514、claude-3-5-sonnet-latest 等",
            example = "claude-sonnet-4-20250514", required = true)
    private String model;

    @Schema(description = "对话消息列表，交替的 user/assistant 轮次", required = true)
    private List<Message> messages;

    @Schema(description = "系统提示词（顶层字段，不在 messages 中）：纯字符串或文本块数组")
    private JsonNode system;

    @Schema(description = "最大生成 Token 数。模型可能在达到上限前停止", example = "1024", required = true)
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    @Schema(description = "采样温度，取值 [0, 2)", example = "0.7")
    private Double temperature;

    @Schema(description = "核采样概率阈值", example = "0.9")
    @JsonProperty("top_p")
    private Double topP;

    @Schema(description = "生成过程中采样候选集的大小", example = "40")
    @JsonProperty("top_k")
    private Integer topK;

    @Schema(description = "是否启用流式输出，默认 false", example = "false")
    private Boolean stream;

    @Schema(description = "自定义停止序列列表")
    @JsonProperty("stop_sequences")
    private List<String> stopSequences;

    @Schema(description = "可调用的工具列表，每个工具需定义 name、description、input_schema")
    private JsonNode tools;

    @Schema(description = "工具调用策略：{\"type\":\"auto\"} / {\"type\":\"any\"} / {\"type\":\"tool\",\"name\":\"...\"} / {\"type\":\"none\"}")
    @JsonProperty("tool_choice")
    private JsonNode toolChoice;

    @Schema(description = "扩展思考（Extended Thinking）配置，如 {\"type\":\"enabled\",\"budget_tokens\":8192}")
    private JsonNode thinking;

    /** 未知字段兜底（透传保留，不参与 Swagger schema） */
    @JsonIgnore
    private Map<String, Object> extra = new LinkedHashMap<>();

    @JsonAnySetter
    public void putExtra(final String key, final Object value) {
        this.extra.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> anyExtra() {
        return this.extra;
    }

    /**
     * 对话消息。content 声明为 JsonNode 以兼容纯字符串与内容块数组
     * （text / image / tool_use / tool_result）两种形态。
     */
    @Data
    @Schema(description = "对话消息")
    public static class Message {

        @Schema(description = "角色：user / assistant（系统提示请使用顶层 system 参数）",
                example = "user", required = true)
        private String role;

        @Schema(description = "消息内容：纯字符串，或内容块数组（text/image/tool_use/tool_result）", required = true)
        private JsonNode content;

        @JsonIgnore
        private Map<String, Object> extra = new LinkedHashMap<>();

        @JsonAnySetter
        public void putExtra(final String key, final Object value) {
            this.extra.put(key, value);
        }

        @JsonAnyGetter
        public Map<String, Object> anyExtra() {
            return this.extra;
        }
    }
}
