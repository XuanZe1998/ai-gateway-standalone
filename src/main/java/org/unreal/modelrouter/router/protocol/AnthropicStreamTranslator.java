package org.unreal.modelrouter.router.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 流式 chunk -> Anthropic SSE 事件 的有状态转换器。
 *
 * OpenAI 流式（chat.completion.chunk）与 Anthropic 流式（message_start /
 * content_block_start / content_block_delta / content_block_stop / message_delta /
 * message_stop）事件模型不同，需在有状态机中逐 chunk 翻译：
 *
 *  - 首个有效 chunk     -> message_start
 *  - 文本 delta         -> content_block_start(text) + content_block_delta(text_delta)
 *  - 工具调用 delta     -> content_block_start(tool_use) + content_block_delta(input_json_delta)
 *  - finish_reason     -> 记录 stop_reason 并关闭所有打开的内容块；
 *                         message_delta/message_stop 推迟到 finalizeIfNeeded 统一发出，
 *                         以便吸收 finish_reason 之后到达的 usage 收尾 chunk
 *  - usage             -> 捕获到 message_start/message_delta 的 usage 字段
 *
 * 每个流式请求创建一个实例（持有跨 chunk 的状态）。
 */
public class AnthropicStreamTranslator {

    private final ObjectMapper mapper;
    private final String messageId;
    private final String model;

    private boolean messageStarted = false;
    private boolean finished = false;
    private String pendingStopReason = null;
    private long inputTokens = 0;
    private long outputTokens = 0;
    private long cacheCreationInputTokens = 0;
    private long cacheReadInputTokens = 0;

    /** 下一个可分配的 Anthropic content_block 索引 */
    private int nextBlockIndex = 0;
    /** 文本块索引；-1 = 尚未开始 */
    private int textBlockIndex = -1;
    /** 文本块是否已关闭 */
    private boolean textBlockStopped = false;
    /** OpenAI tool_calls 的 index -> Anthropic content_block 索引（打开中的工具块） */
    private final Map<Integer, Integer> openToolBlocks = new HashMap<>();

    public AnthropicStreamTranslator(final ObjectMapper mapper, final String messageId, final String model) {
        this.mapper = mapper;
        this.messageId = messageId;
        this.model = model;
    }

    /**
     * 输入一个 OpenAI SSE chunk 的 JSON 字符串，输出 0..N 个 Anthropic SSE 事件。
     * 每个事件为 {"event": <eventName>, "data": <jsonString>}。
     */
    public List<SseEvent> translateChunk(final String openaiChunkJson) {
        List<SseEvent> out = new ArrayList<>();

        final JsonNode chunk;
        try {
            chunk = mapper.readTree(openaiChunkJson);
        } catch (Exception e) {
            return out;
        }

        // 先捕获 usage（可能在 finish_reason 之前/之后的任意 chunk，包括 choices 为空的收尾 chunk）。
        // 必须在使用前捕获：usage 与 finish_reason 常分属不同 chunk，finish_reason 先到时
        // 不能立即结束，否则后续带 usage 的 chunk 会被丢弃导致 output_tokens=0。
        JsonNode usage = chunk.path("usage");
        if (usage.isObject()) {
            inputTokens = usage.path("prompt_tokens").asLong(inputTokens);
            outputTokens = usage.path("completion_tokens").asLong(outputTokens);
            cacheCreationInputTokens = usage.path("cache_creation_input_tokens").asLong(cacheCreationInputTokens);
            cacheReadInputTokens = usage.path("cache_read_input_tokens").asLong(cacheReadInputTokens);
        }

        JsonNode choices = chunk.path("choices");
        JsonNode firstChoice = (choices.isArray() && choices.size() > 0) ? choices.get(0) : null;
        String finishReason = firstChoice != null && firstChoice.hasNonNull("finish_reason")
                ? firstChoice.path("finish_reason").asText() : null;

        // 已收到 finish_reason：后续 chunk 仅用于吸收 usage，不再产生业务事件
        if (pendingStopReason != null) {
            return out;
        }

        // 1. message_start（首个 chunk 时发出）
        if (!messageStarted) {
            out.add(new SseEvent("message_start", buildMessageStart()));
            messageStarted = true;
        }

        // 2. 内容增量：文本 + 工具调用
        if (firstChoice != null) {
            JsonNode delta = firstChoice.path("delta");

            String text = delta.path("content").asText(null);
            if (text != null && !text.isEmpty()) {
                if (textBlockIndex < 0) {
                    textBlockIndex = nextBlockIndex++;
                    out.add(new SseEvent("content_block_start", buildTextBlockStart(textBlockIndex)));
                }
                out.add(new SseEvent("content_block_delta", buildTextDelta(textBlockIndex, text)));
            }

            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    out.addAll(translateToolCallDelta(tc));
                }
            }
        }

        // 3. finish_reason：记录并关闭所有打开的内容块，message_delta/message_stop 推迟到
        //    finalizeIfNeeded，以便先吸收可能随后到达的 usage 收尾 chunk。
        if (finishReason != null) {
            closeOpenBlocks(out);
            pendingStopReason = finishReason;
        }

        return out;
    }

    /**
     * 流结束时统一收尾：发出 message_delta（携带最终 input/output_tokens）+ message_stop。
     * 无论是否收到 finish_reason 都要调用一次（由 service 在流完成时触发），
     * 保证 usage 在发送前已从所有 chunk（含 finish_reason 之后的收尾 chunk）捕获完整。
     */
    public List<SseEvent> finalizeIfNeeded() {
        List<SseEvent> out = new ArrayList<>();
        if (finished) {
            return out;
        }
        if (!messageStarted) {
            out.add(new SseEvent("message_start", buildMessageStart()));
            messageStarted = true;
        }
        closeOpenBlocks(out);
        String stopReason = pendingStopReason != null ? pendingStopReason : "stop";
        out.add(new SseEvent("message_delta", buildMessageDelta(stopReason)));
        out.add(new SseEvent("message_stop", buildMessageStop()));
        finished = true;
        return out;
    }

    // ==================== 工具调用增量 ====================

    /**
     * OpenAI delta.tool_calls 元素 -> Anthropic tool_use 块事件。
     * 首个携带 id/name 的 chunk 开启 content_block_start(tool_use)，
     * 后续 arguments 增量片段转换为 input_json_delta。
     */
    private List<SseEvent> translateToolCallDelta(final JsonNode toolCall) {
        List<SseEvent> out = new ArrayList<>();
        int openaiIndex = toolCall.path("index").asInt(0);

        Integer blockIndex = openToolBlocks.get(openaiIndex);
        if (blockIndex == null) {
            // 新工具块：先关闭打开中的文本块
            if (textBlockIndex >= 0 && !textBlockStopped) {
                out.add(new SseEvent("content_block_stop", buildContentBlockStop(textBlockIndex)));
                textBlockStopped = true;
            }
            blockIndex = nextBlockIndex++;
            openToolBlocks.put(openaiIndex, blockIndex);

            String id = toolCall.path("id").asText("toolu_" + blockIndex);
            String name = toolCall.path("function").path("name").asText("");
            out.add(new SseEvent("content_block_start", buildToolUseBlockStart(blockIndex, id, name)));
        }

        String argsFragment = toolCall.path("function").path("arguments").asText(null);
        if (argsFragment != null && !argsFragment.isEmpty()) {
            out.add(new SseEvent("content_block_delta", buildInputJsonDelta(blockIndex, argsFragment)));
        }
        return out;
    }

    /** 关闭所有打开的内容块（文本块 + 全部工具块）。 */
    private void closeOpenBlocks(final List<SseEvent> out) {
        if (textBlockIndex >= 0 && !textBlockStopped) {
            out.add(new SseEvent("content_block_stop", buildContentBlockStop(textBlockIndex)));
            textBlockStopped = true;
        }
        if (!openToolBlocks.isEmpty()) {
            openToolBlocks.values().stream().sorted().forEach(idx ->
                    out.add(new SseEvent("content_block_stop", buildContentBlockStop(idx))));
            openToolBlocks.clear();
        }
    }

    // ==================== 事件构建 ====================

    private String buildMessageStart() {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "message_start");
        ObjectNode message = mapper.createObjectNode();
        message.put("id", messageId);
        message.put("type", "message");
        message.put("role", "assistant");
        message.set("content", mapper.createArrayNode());
        message.put("model", model);
        message.putNull("stop_reason");
        message.putNull("stop_sequence");
        ObjectNode usage = mapper.createObjectNode();
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", 0);
        message.set("usage", usage);
        event.set("message", message);
        return event.toString();
    }

    private String buildTextBlockStart(final int index) {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "content_block_start");
        event.put("index", index);
        ObjectNode block = mapper.createObjectNode();
        block.put("type", "text");
        block.put("text", "");
        event.set("content_block", block);
        return event.toString();
    }

    private String buildToolUseBlockStart(final int index, final String id, final String name) {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "content_block_start");
        event.put("index", index);
        ObjectNode block = mapper.createObjectNode();
        block.put("type", "tool_use");
        block.put("id", id);
        block.put("name", name);
        block.set("input", mapper.createObjectNode());
        event.set("content_block", block);
        return event.toString();
    }

    private String buildTextDelta(final int index, final String text) {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "content_block_delta");
        event.put("index", index);
        ObjectNode delta = mapper.createObjectNode();
        delta.put("type", "text_delta");
        delta.put("text", text);
        event.set("delta", delta);
        return event.toString();
    }

    private String buildInputJsonDelta(final int index, final String partialJson) {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "content_block_delta");
        event.put("index", index);
        ObjectNode delta = mapper.createObjectNode();
        delta.put("type", "input_json_delta");
        delta.put("partial_json", partialJson);
        event.set("delta", delta);
        return event.toString();
    }

    private String buildContentBlockStop(final int index) {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "content_block_stop");
        event.put("index", index);
        return event.toString();
    }

    private String buildMessageDelta(final String openaiFinishReason) {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "message_delta");
        ObjectNode delta = mapper.createObjectNode();
        delta.put("stop_reason", AnthropicConverter.mapStopReason(openaiFinishReason));
        delta.putNull("stop_sequence");
        event.set("delta", delta);
        ObjectNode usage = mapper.createObjectNode();
        // message_start 发出时上游尚未返回 usage（OpenAI 上游仅在流末尾返回），
        // 故在 message_delta 中同时携带 input_tokens，客户端只读本事件即可拿到完整用量
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", outputTokens);
        usage.put("cache_creation_input_tokens", cacheCreationInputTokens);
        usage.put("cache_read_input_tokens", cacheReadInputTokens);
        event.set("usage", usage);
        return event.toString();
    }

    private String buildMessageStop() {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "message_stop");
        return event.toString();
    }

    /** 一个 Anthropic SSE 事件（event 名 + data JSON）。 */
    public record SseEvent(String event, String data) {}
}
