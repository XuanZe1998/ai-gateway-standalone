package org.unreal.modelrouter.router.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic <-> OpenAI 协议转换器。
 *
 * 聚合平台的上游（天翼云 / 阿里云 / 火山引擎 / Kimi / GLM 等）均为 OpenAI 兼容协议，
 * 因此对外的 Anthropic 端点（/v1/messages）必须在网关层做双向翻译：
 *  - 请求：Anthropic -> OpenAI（system 顶层字段并入 messages、content 块数组转 OpenAI 多模态、
 *    stop_sequences -> stop、tool_choice 类型映射等）
 *  - 响应：OpenAI -> Anthropic（choices -> content 块、finish_reason -> stop_reason、
 *    usage.prompt/completion_tokens -> input/output_tokens、id 前缀 chatcmpl -> msg）
 *
 * 全部为纯函数（JsonNode 进 / JsonNode 出），便于单元测试。
 */
public final class AnthropicConverter {

    private AnthropicConverter() {}

    // ==================== 请求：Anthropic -> OpenAI ====================

    /**
     * 将 Anthropic /v1/messages 请求体转换为 OpenAI /v1/chat/completions 请求体。
     */
    public static ObjectNode convertRequestToOpenAI(final JsonNode anthropicReq, final ObjectMapper mapper) {
        ObjectNode openai = mapper.createObjectNode();

        // model 原样透传（网关按它选实例）
        if (anthropicReq.hasNonNull("model")) {
            openai.put("model", anthropicReq.get("model").asText());
        }

        // messages：转换 content 块数组；system 顶层字段插入为 system 消息
        ArrayNode openaiMessages = mapper.createArrayNode();

        JsonNode system = anthropicReq.get("system");
        if (system != null && !system.isNull()) {
            ObjectNode sysMsg = mapper.createObjectNode();
            sysMsg.put("role", "system");
            sysMsg.put("content", extractText(system));
            openaiMessages.add(sysMsg);
        }

        JsonNode messages = anthropicReq.get("messages");
        if (messages != null && messages.isArray()) {
            for (JsonNode msg : messages) {
                convertMessage(msg, openaiMessages, mapper);
            }
        }
        openai.set("messages", openaiMessages);

        // max_tokens 必填（Anthropic）；OpenAI 用 max_completion_tokens 优先
        if (anthropicReq.hasNonNull("max_tokens")) {
            openai.put("max_tokens", anthropicReq.get("max_tokens").asInt());
        }

        copyDouble(anthropicReq, openai, "temperature");
        copyDouble(anthropicReq, openai, "top_p");
        copyInt(anthropicReq, openai, "top_k");

        if (anthropicReq.hasNonNull("stream")) {
            openai.put("stream", anthropicReq.get("stream").asBoolean());
        }

        // stop_sequences -> stop
        JsonNode stopSeq = anthropicReq.get("stop_sequences");
        if (stopSeq != null && stopSeq.isArray() && stopSeq.size() > 0) {
            openai.set("stop", stopSeq);
        }

        // tools：Anthropic {name, description, input_schema} -> OpenAI {type:"function", function:{...}}
        if (anthropicReq.hasNonNull("tools")) {
            ArrayNode openaiTools = mapper.createArrayNode();
            for (JsonNode tool : anthropicReq.get("tools")) {
                ObjectNode fn = mapper.createObjectNode();
                fn.put("type", "function");
                ObjectNode function = mapper.createObjectNode();
                function.put("name", tool.path("name").asText(""));
                if (tool.hasNonNull("description")) {
                    function.put("description", tool.get("description").asText());
                }
                if (tool.has("input_schema")) {
                    function.set("parameters", tool.get("input_schema"));
                }
                fn.set("function", function);
                openaiTools.add(fn);
            }
            openai.set("tools", openaiTools);
        }

        // tool_choice 类型映射
        JsonNode toolChoice = anthropicReq.get("tool_choice");
        if (toolChoice != null && toolChoice.isObject()) {
            String type = toolChoice.path("type").asText("");
            switch (type) {
                case "auto" -> openai.put("tool_choice", "auto");
                case "none" -> openai.put("tool_choice", "none");
                case "any" -> openai.put("tool_choice", "required");
                case "tool" -> {
                    ObjectNode tc = mapper.createObjectNode();
                    tc.put("type", "function");
                    ObjectNode fn = mapper.createObjectNode();
                    fn.put("name", toolChoice.path("name").asText(""));
                    tc.set("function", fn);
                    openai.set("tool_choice", tc);
                }
                default -> { /* 未知类型忽略 */ }
            }
        }

        return openai;
    }

    /**
     * 转换单条 Anthropic 消息，向 out 追加 1..N 条 OpenAI 消息。
     * 一条 Anthropic 消息可能拆成多条 OpenAI 消息：
     *  - assistant 的 tool_use 块 -> 该 assistant 消息的 tool_calls
     *  - user 的 tool_result 块 -> 独立的 role:"tool" 消息
     */
    private static void convertMessage(final JsonNode msg, final ArrayNode out, final ObjectMapper mapper) {
        String role = msg.path("role").asText("user");
        JsonNode content = msg.get("content");

        // 纯文本消息
        if (content == null || !content.isArray()) {
            ObjectNode m = mapper.createObjectNode();
            m.put("role", role);
            m.put("content", content != null && !content.isNull() ? content.asText("") : "");
            out.add(m);
            return;
        }

        // content 块数组：按块类型分类
        ArrayNode parts = mapper.createArrayNode();        // text/image -> OpenAI content
        ArrayNode toolCalls = mapper.createArrayNode();    // assistant tool_use -> tool_calls
        List<ObjectNode> toolMessages = new ArrayList<>(); // user tool_result -> role:"tool" 消息
        boolean onlyText = true;

        for (JsonNode block : content) {
            String type = block.path("type").asText("");
            switch (type) {
                case "text" -> {
                    ObjectNode t = mapper.createObjectNode();
                    t.put("type", "text");
                    t.put("text", block.path("text").asText(""));
                    parts.add(t);
                }
                case "image" -> {
                    onlyText = false;
                    parts.add(convertImageBlock(block, mapper));
                }
                case "tool_use" -> toolCalls.add(convertToolUseBlock(block, mapper));
                case "tool_result" -> toolMessages.add(convertToolResultBlock(block, mapper));
                default -> {
                    // 未知块类型：保留为文本描述，避免丢失
                    onlyText = false;
                    ObjectNode t = mapper.createObjectNode();
                    t.put("type", "text");
                    t.put("text", block.toString());
                    parts.add(t);
                }
            }
        }

        // tool_result 对应的 tool 消息先输出（OpenAI 约定 tool 消息紧随 assistant tool_calls 之后）
        toolMessages.forEach(out::add);

        // 主消息：有 text/image 部分、有 tool_calls、或本消息不含 tool_result 时输出
        boolean hasParts = parts.size() > 0;
        if (hasParts || toolCalls.size() > 0 || toolMessages.isEmpty()) {
            ObjectNode m = mapper.createObjectNode();
            m.put("role", role);
            if (hasParts) {
                // 纯文本单块简化为字符串（多数 OpenAI 上游更兼容）
                if (onlyText && parts.size() == 1) {
                    m.put("content", parts.get(0).path("text").asText(""));
                } else {
                    m.set("content", parts);
                }
            } else if (toolCalls.size() > 0) {
                m.putNull("content");
            } else {
                m.put("content", "");
            }
            if (toolCalls.size() > 0) {
                m.set("tool_calls", toolCalls);
            }
            out.add(m);
        }
    }

    /** Anthropic image 块 -> OpenAI image_url 部分（base64 转 data URL，url 直传）。 */
    private static ObjectNode convertImageBlock(final JsonNode block, final ObjectMapper mapper) {
        ObjectNode img = mapper.createObjectNode();
        img.put("type", "image_url");
        ObjectNode imageUrl = mapper.createObjectNode();
        JsonNode source = block.path("source");
        if ("base64".equals(source.path("type").asText(""))) {
            String mediaType = source.path("media_type").asText("image/jpeg");
            imageUrl.put("url", "data:" + mediaType + ";base64," + source.path("data").asText(""));
        } else {
            imageUrl.put("url", source.path("url").asText(""));
        }
        img.set("image_url", imageUrl);
        return img;
    }

    /** Anthropic tool_use 块 -> OpenAI tool_calls 元素（input 对象序列化为 arguments JSON 字符串）。 */
    private static ObjectNode convertToolUseBlock(final JsonNode block, final ObjectMapper mapper) {
        ObjectNode tc = mapper.createObjectNode();
        tc.put("id", block.path("id").asText(""));
        tc.put("type", "function");
        ObjectNode function = mapper.createObjectNode();
        function.put("name", block.path("name").asText(""));
        JsonNode input = block.get("input");
        function.put("arguments", input != null ? input.toString() : "{}");
        tc.set("function", function);
        return tc;
    }

    /** Anthropic tool_result 块 -> OpenAI role:"tool" 消息（content 支持字符串或文本块数组）。 */
    private static ObjectNode convertToolResultBlock(final JsonNode block, final ObjectMapper mapper) {
        ObjectNode tm = mapper.createObjectNode();
        tm.put("role", "tool");
        tm.put("tool_call_id", block.path("tool_use_id").asText(""));
        JsonNode trContent = block.get("content");
        tm.put("content", trContent != null ? extractText(trContent) : "");
        return tm;
    }

    /** system 字段可为字符串或文本块数组，统一提取为字符串。 */
    private static String extractText(final JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : node) {
                if ("text".equals(item.path("type").asText(""))) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(item.path("text").asText(""));
                }
            }
            return sb.toString();
        }
        return node.asText("");
    }

    // ==================== 响应：OpenAI -> Anthropic ====================

    /**
     * 将 OpenAI 非流式响应体转换为 Anthropic /v1/messages 响应体。
     */
    public static ObjectNode convertResponseToAnthropic(final JsonNode openaiResp, final ObjectMapper mapper) {
        ObjectNode anthropic = mapper.createObjectNode();

        String id = openaiResp.path("id").asText("");
        anthropic.put("id", toAnthropicId(id));
        anthropic.put("type", "message");
        anthropic.put("role", "assistant");

        // content：取第一个 choice 的 message.content
        ArrayNode content = mapper.createArrayNode();
        JsonNode choices = openaiResp.path("choices");
        String finishReason = null;
        if (choices.isArray() && choices.size() > 0) {
            JsonNode first = choices.get(0);
            finishReason = first.path("finish_reason").asText(null);
            JsonNode message = first.path("message");
            String text = message.path("content").asText(null);
            if (text != null) {
                ObjectNode textBlock = mapper.createObjectNode();
                textBlock.put("type", "text");
                textBlock.put("text", text);
                content.add(textBlock);
            }
            // tool_calls -> tool_use 块
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode call : toolCalls) {
                    ObjectNode toolUse = mapper.createObjectNode();
                    toolUse.put("type", "tool_use");
                    toolUse.put("id", call.path("id").asText(""));
                    toolUse.put("name", call.path("function").path("name").asText(""));
                    toolUse.set("input", parseArguments(call.path("function").path("arguments"), mapper));
                    content.add(toolUse);
                }
            }
        }
        anthropic.set("content", content);

        anthropic.put("model", openaiResp.path("model").asText(""));
        anthropic.put("stop_reason", mapStopReason(finishReason));
        anthropic.putNull("stop_sequence");

        // usage：prompt/completion_tokens -> input/output_tokens
        ObjectNode usage = mapper.createObjectNode();
        JsonNode u = openaiResp.path("usage");
        usage.put("input_tokens", u.path("prompt_tokens").asLong(0));
        usage.put("output_tokens", u.path("completion_tokens").asLong(0));
        // 补全 cache 字段（上游若直接返回 Anthropic 风格则不丢弃；计费归一化在 TokenUsageExtractor 完成，此处仅保证客户端展示完整）
        usage.put("cache_creation_input_tokens", u.path("cache_creation_input_tokens").asLong(0));
        usage.put("cache_read_input_tokens", u.path("cache_read_input_tokens").asLong(0));
        anthropic.set("usage", usage);

        return anthropic;
    }

    /**
     * 解析 OpenAI tool_calls 的 arguments 字段为 JsonNode。
     * 兼容两种上游：标准 OpenAI 返回 JSON 字符串；部分国产上游（如 Kimi）直接返回对象。
     */
    private static JsonNode parseArguments(final JsonNode args, final ObjectMapper mapper) {
        if (args == null || args.isNull() || args.isMissingNode()) {
            return mapper.createObjectNode();
        }
        if (args.isObject()) {
            // 上游直接返回对象（非标准但存在），直接使用
            return args;
        }
        try {
            return mapper.readTree(args.asText("{}"));
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    // ==================== 错误：OpenAI -> Anthropic ====================

    /**
     * 将上游（OpenAI 格式或网关 ResponseStatusException）错误体转换为 Anthropic 错误格式。
     * 若上游已是合法 JSON 则提取 message，否则用默认文案。
     */
    public static ObjectNode convertErrorToAnthropic(final String upstreamBody,
                                                     final String fallbackMessage,
                                                     final ObjectMapper mapper) {
        String message = fallbackMessage;
        String type = "api_error";
        try {
            JsonNode node = mapper.readTree(upstreamBody);
            JsonNode error = node.path("error");
            if (error.isObject()) {
                String msg = error.path("message").asText(null);
                if (msg != null) {
                    message = msg;
                }
                String code = error.path("code").asText("");
                type = mapErrorType(code);
            }
        } catch (Exception ignored) {
            // 非 JSON，使用兜底文案
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "error");
        ObjectNode error = mapper.createObjectNode();
        error.put("type", type);
        error.put("message", message);
        root.set("error", error);
        return root;
    }

    // ==================== 映射辅助 ====================

    /** OpenAI id (chatcmpl-xxx) -> Anthropic id (msg_xxx)。 */
    private static String toAnthropicId(final String openaiId) {
        if (openaiId != null && openaiId.startsWith("chatcmpl-")) {
            return "msg_" + openaiId.substring("chatcmpl-".length());
        }
        if (openaiId != null && openaiId.startsWith("msg_")) {
            return openaiId;
        }
        return "msg_" + (openaiId != null ? openaiId : "");
    }

    /** OpenAI finish_reason -> Anthropic stop_reason。 */
    public static String mapStopReason(final String finishReason) {
        if (finishReason == null) {
            return "end_turn";
        }
        return switch (finishReason) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "tool_calls", "function_call" -> "tool_use";
            case "content_filter" -> "refusal";
            default -> "end_turn";
        };
    }

    /** Anthropic stop_reason -> OpenAI finish_reason（流式用，反向）。 */
    public static String mapFinishReason(final String stopReason) {
        if (stopReason == null) {
            return "stop";
        }
        return switch (stopReason) {
            case "end_turn" -> "stop";
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            default -> "stop";
        };
    }

    private static String mapErrorType(final String openaiCode) {
        if (openaiCode == null) {
            return "api_error";
        }
        return switch (openaiCode) {
            case "invalid_api_key", "invalid_request_error" -> "invalid_request_error";
            case "authentication_error" -> "authentication_error";
            case "permission_error" -> "permission_error";
            case "model_not_found", "not_found" -> "not_found_error";
            case "rate_limit_exceeded" -> "rate_limit_error";
            case "context_length_exceeded" -> "invalid_request_error";
            default -> "api_error";
        };
    }

    private static void copyDouble(final JsonNode from, final ObjectNode to, final String field) {
        if (from.hasNonNull(field)) {
            to.put(field, from.get(field).asDouble());
        }
    }

    private static void copyInt(final JsonNode from, final ObjectNode to, final String field) {
        if (from.hasNonNull(field)) {
            to.put(field, from.get(field).asInt());
        }
    }
}
