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
 * OpenAI 协议对话补全请求（/v1/chat/completions）。
 *
 * 已知字段按接口文档声明为类型化字段（Swagger 自动生成完整 schema）；
 * 未知字段（如 seed、response_format 及未来新增字段）通过 {@link JsonAnySetter} 兜底，
 * 序列化时经 {@link JsonAnyGetter} 原样平铺回顶层，保证透传不丢字段。
 */
@Data
@Schema(description = "OpenAI 协议对话补全请求")
public class OpenAiChatRequest {

    @Schema(description = "模型名称，如 qwen-max、deepseek-chat 等", example = "qwen-max", required = true)
    private String model;

    @Schema(description = "对话消息列表", required = true)
    private List<Message> messages;

    @Schema(description = "采样温度，取值 [0, 2)", example = "0.7")
    private Double temperature;

    @Schema(description = "核采样概率阈值，取值 (0, 1]", example = "0.9")
    @JsonProperty("top_p")
    private Double topP;

    @Schema(description = "生成最大 Token 数（建议使用 max_completion_tokens）", example = "2048")
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    @Schema(description = "生成最大 Token 数（含推理 Token），优先于 max_tokens", example = "2048")
    @JsonProperty("max_completion_tokens")
    private Integer maxCompletionTokens;

    @Schema(description = "生成候选回复数量", example = "1")
    private Integer n;

    @Schema(description = "是否启用流式输出，默认 false", example = "false")
    private Boolean stream;

    @Schema(description = "流式选项，{\"include_usage\": true} 在末尾返回用量")
    @JsonProperty("stream_options")
    private JsonNode streamOptions;

    @Schema(description = "停止生成的字符串列表")
    private JsonNode stop;

    @Schema(description = "随机种子，用于提升可复现性", example = "42")
    private Integer seed;

    @Schema(description = "存在惩罚，取值 [-2, 2]", example = "0")
    @JsonProperty("presence_penalty")
    private Double presencePenalty;

    @Schema(description = "可调用的工具列表")
    private JsonNode tools;

    @Schema(description = "工具调用策略：\"auto\" / \"none\" / 指定工具")
    @JsonProperty("tool_choice")
    private JsonNode toolChoice;

    @Schema(description = "输出格式约束，如 {\"type\": \"json_object\"}")
    @JsonProperty("response_format")
    private JsonNode responseFormat;

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
     * 对话消息。content 声明为 JsonNode 以兼容字符串与多模态内容块数组两种形态。
     */
    @Data
    @Schema(description = "对话消息")
    public static class Message {

        @Schema(description = "角色：system / developer / user / assistant / tool", example = "user", required = true)
        private String role;

        @Schema(description = "消息内容：文本字符串，或多模态内容块数组", required = true)
        private JsonNode content;

        @Schema(description = "消息参与者名称（可选）")
        private String name;

        @Schema(description = "tool 角色消息对应的工具调用 ID")
        @JsonProperty("tool_call_id")
        private String toolCallId;

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
