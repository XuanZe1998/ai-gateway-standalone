package org.unreal.modelrouter.router.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AnthropicConverter（请求/响应/错误转换）与 AnthropicStreamTranslator（流式事件翻译）单元测试。
 */
class AnthropicConverterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ==================== 请求转换：Anthropic -> OpenAI ====================

    @Test
    void request_simpleTextMessage() throws Exception {
        String anthropic = """
            {"model":"claude-sonnet-4","max_tokens":1024,
             "messages":[{"role":"user","content":"你好"}]}
            """;
        ObjectNode openai = AnthropicConverter.convertRequestToOpenAI(mapper.readTree(anthropic), mapper);

        assertEquals("claude-sonnet-4", openai.get("model").asText());
        assertEquals(1024, openai.get("max_tokens").asInt());
        JsonNode messages = openai.get("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).get("role").asText());
        assertEquals("你好", messages.get(0).get("content").asText());
    }

    @Test
    void request_systemFieldBecomesSystemMessage() throws Exception {
        String anthropic = """
            {"model":"m","max_tokens":100,"system":"你是助手",
             "messages":[{"role":"user","content":"hi"}]}
            """;
        ObjectNode openai = AnthropicConverter.convertRequestToOpenAI(mapper.readTree(anthropic), mapper);
        JsonNode messages = openai.get("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("你是助手", messages.get(0).get("content").asText());
        assertEquals("user", messages.get(1).get("role").asText());
    }

    @Test
    void request_contentBlockArray_singleText_simplifiedToString() throws Exception {
        String anthropic = """
            {"model":"m","max_tokens":100,
             "messages":[{"role":"user","content":[{"type":"text","text":"你好"}]}]}
            """;
        ObjectNode openai = AnthropicConverter.convertRequestToOpenAI(mapper.readTree(anthropic), mapper);
        JsonNode content = openai.get("messages").get(0).get("content");
        assertTrue(content.isTextual());
        assertEquals("你好", content.asText());
    }

    @Test
    void request_imageBlock_convertedToImageUrl() throws Exception {
        String anthropic = """
            {"model":"m","max_tokens":100,
             "messages":[{"role":"user","content":[
                {"type":"text","text":"看图"},
                {"type":"image","source":{"type":"url","url":"https://x.com/a.jpg"}}
             ]}]}
            """;
        ObjectNode openai = AnthropicConverter.convertRequestToOpenAI(mapper.readTree(anthropic), mapper);
        JsonNode content = openai.get("messages").get(0).get("content");
        assertTrue(content.isArray());
        assertEquals("text", content.get(0).get("type").asText());
        assertEquals("image_url", content.get(1).get("type").asText());
        assertEquals("https://x.com/a.jpg", content.get(1).get("image_url").get("url").asText());
    }

    @Test
    void request_base64Image_convertedToDataUrl() throws Exception {
        String anthropic = """
            {"model":"m","max_tokens":100,
             "messages":[{"role":"user","content":[
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBOR"}}
             ]}]}
            """;
        ObjectNode openai = AnthropicConverter.convertRequestToOpenAI(mapper.readTree(anthropic), mapper);
        JsonNode content = openai.get("messages").get(0).get("content");
        assertEquals("data:image/png;base64,iVBOR", content.get(0).get("image_url").get("url").asText());
    }

    @Test
    void request_stopSequencesAndParams() throws Exception {
        String anthropic = """
            {"model":"m","max_tokens":100,"temperature":0.7,"top_p":0.9,"top_k":40,
             "stop_sequences":["END"],"stream":true,
             "messages":[{"role":"user","content":"hi"}]}
            """;
        ObjectNode openai = AnthropicConverter.convertRequestToOpenAI(mapper.readTree(anthropic), mapper);
        assertEquals(0.7, openai.get("temperature").asDouble());
        assertEquals(0.9, openai.get("top_p").asDouble());
        assertEquals(40, openai.get("top_k").asInt());
        assertTrue(openai.get("stream").asBoolean());
        assertEquals("END", openai.get("stop").get(0).asText());
    }

    @Test
    void request_toolChoiceMapping() throws Exception {
        assertEquals("auto", toolChoice("{\"type\":\"auto\"}").asText());
        assertEquals("none", toolChoice("{\"type\":\"none\"}").asText());
        assertEquals("required", toolChoice("{\"type\":\"any\"}").asText());
        JsonNode specific = toolChoice("{\"type\":\"tool\",\"name\":\"get_weather\"}");
        assertEquals("function", specific.get("type").asText());
        assertEquals("get_weather", specific.get("function").get("name").asText());
    }

    private JsonNode toolChoice(String toolChoiceJson) throws Exception {
        String anthropic = """
            {"model":"m","max_tokens":100,"tool_choice":%s,
             "messages":[{"role":"user","content":"hi"}]}
            """.formatted(toolChoiceJson);
        ObjectNode openai = AnthropicConverter.convertRequestToOpenAI(mapper.readTree(anthropic), mapper);
        return openai.get("tool_choice");
    }

    // ==================== 工具调用：请求转换 ====================

    @Test
    void request_toolsConvertedToOpenAIFunctions() throws Exception {
        String anthropic = """
            {"model":"m","max_tokens":100,
             "tools":[{"name":"get_weather","description":"查询天气",
                       "input_schema":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}],
             "messages":[{"role":"user","content":"北京天气"}]}
            """;
        ObjectNode openai = AnthropicConverter.convertRequestToOpenAI(mapper.readTree(anthropic), mapper);

        JsonNode tools = openai.get("tools");
        assertEquals(1, tools.size());
        JsonNode tool = tools.get(0);
        assertEquals("function", tool.get("type").asText());
        assertEquals("get_weather", tool.get("function").get("name").asText());
        assertEquals("查询天气", tool.get("function").get("description").asText());
        // input_schema -> parameters 原样保留
        assertEquals("object", tool.get("function").get("parameters").get("type").asText());
        assertTrue(tool.get("function").get("parameters").get("properties").has("city"));
    }

    @Test
    void request_toolUseBlock_becomesAssistantToolCalls() throws Exception {
        String anthropic = """
            {"model":"m","max_tokens":100,
             "messages":[
               {"role":"user","content":"北京天气"},
               {"role":"assistant","content":[
                   {"type":"text","text":"我来查一下"},
                   {"type":"tool_use","id":"toolu_01","name":"get_weather","input":{"city":"北京"}}
               ]}
             ]}
            """;
        ObjectNode openai = AnthropicConverter.convertRequestToOpenAI(mapper.readTree(anthropic), mapper);

        JsonNode assistant = openai.get("messages").get(1);
        assertEquals("assistant", assistant.get("role").asText());
        assertEquals("我来查一下", assistant.get("content").asText());
        JsonNode toolCalls = assistant.get("tool_calls");
        assertEquals(1, toolCalls.size());
        assertEquals("toolu_01", toolCalls.get(0).get("id").asText());
        assertEquals("function", toolCalls.get(0).get("type").asText());
        assertEquals("get_weather", toolCalls.get(0).get("function").get("name").asText());
        // input 对象 -> arguments JSON 字符串
        assertEquals("{\"city\":\"北京\"}", toolCalls.get(0).get("function").get("arguments").asText());
    }

    @Test
    void request_toolResultBlock_becomesToolMessage() throws Exception {
        String anthropic = """
            {"model":"m","max_tokens":100,
             "messages":[
               {"role":"user","content":[
                   {"type":"tool_result","tool_use_id":"toolu_01","content":"晴，25℃"}
               ]}
             ]}
            """;
        ObjectNode openai = AnthropicConverter.convertRequestToOpenAI(mapper.readTree(anthropic), mapper);

        JsonNode messages = openai.get("messages");
        // 纯 tool_result 的 user 消息 -> 仅输出 role:"tool" 消息
        assertEquals(1, messages.size());
        assertEquals("tool", messages.get(0).get("role").asText());
        assertEquals("toolu_01", messages.get(0).get("tool_call_id").asText());
        assertEquals("晴，25℃", messages.get(0).get("content").asText());
    }

    @Test
    void request_toolResultWithArrayContent_extractsText() throws Exception {
        String anthropic = """
            {"model":"m","max_tokens":100,
             "messages":[
               {"role":"user","content":[
                   {"type":"tool_result","tool_use_id":"toolu_01",
                    "content":[{"type":"text","text":"第一行"},{"type":"text","text":"第二行"}]}
               ]}
             ]}
            """;
        ObjectNode openai = AnthropicConverter.convertRequestToOpenAI(mapper.readTree(anthropic), mapper);
        assertEquals("第一行\n第二行", openai.get("messages").get(0).get("content").asText());
    }

    // ==================== 工具调用：流式翻译 ====================

    @Test
    void stream_toolCall_producesToolUseEvents() {
        AnthropicStreamTranslator translator =
                new AnthropicStreamTranslator(mapper, "msg_tc", "m");

        // 工具调用首 chunk（携带 id + name）
        List<AnthropicStreamTranslator.SseEvent> e1 = translator.translateChunk(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}}]}}]}");
        var start = e1.stream().filter(e -> e.event().equals("content_block_start")).findFirst().orElseThrow();
        assertTrue(start.data().contains("\"type\":\"tool_use\""));
        assertTrue(start.data().contains("call_1"));
        assertTrue(start.data().contains("get_weather"));

        // arguments 增量片段 -> input_json_delta
        List<AnthropicStreamTranslator.SseEvent> e2 = translator.translateChunk(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"{\\\"city\\\":\"}}]}}]}");
        var delta = e2.stream().filter(e -> e.event().equals("content_block_delta")).findFirst().orElseThrow();
        assertTrue(delta.data().contains("input_json_delta"));

        // finish_reason：工具块被关闭
        List<AnthropicStreamTranslator.SseEvent> e3 = translator.translateChunk(
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
        assertTrue(e3.stream().anyMatch(e -> e.event().equals("content_block_stop")));

        // message_delta 的 stop_reason 映射为 tool_use
        List<AnthropicStreamTranslator.SseEvent> tail = translator.finalizeIfNeeded();
        String messageDelta = tail.stream()
                .filter(e -> e.event().equals("message_delta")).findFirst().orElseThrow().data();
        assertTrue(messageDelta.contains("tool_use"));
    }

    @Test
    void stream_textThenToolCall_blockIndicesSequential() {
        AnthropicStreamTranslator translator =
                new AnthropicStreamTranslator(mapper, "msg_mix", "m");

        // 先有文本（块 0）
        translator.translateChunk("{\"choices\":[{\"delta\":{\"content\":\"想一下\"}}]}");
        // 再来工具调用（块 1，且文本块应先被关闭）
        List<AnthropicStreamTranslator.SseEvent> events = translator.translateChunk(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                + "\"function\":{\"name\":\"f\",\"arguments\":\"\"}}]}}]}");

        // 文本块(index 0)先 stop，再 tool_use 块(index 1)start
        assertTrue(events.stream().anyMatch(e -> e.event().equals("content_block_stop")
                && e.data().contains("\"index\":0")));
        var toolStart = events.stream().filter(e -> e.event().equals("content_block_start")
                && e.data().contains("tool_use")).findFirst().orElseThrow();
        assertTrue(toolStart.data().contains("\"index\":1"));
    }

    // ==================== 响应转换：OpenAI -> Anthropic ====================

    @Test
    void response_textMessage() throws Exception {
        String openai = """
            {"id":"chatcmpl-abc123","object":"chat.completion","created":1779964861,"model":"qwen-max",
             "choices":[{"index":0,"message":{"role":"assistant","content":"人工智能是..."},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":15,"completion_tokens":42,"total_tokens":57}}
            """;
        ObjectNode anthropic = AnthropicConverter.convertResponseToAnthropic(mapper.readTree(openai), mapper);

        assertEquals("msg_abc123", anthropic.get("id").asText());
        assertEquals("message", anthropic.get("type").asText());
        assertEquals("assistant", anthropic.get("role").asText());
        assertEquals("text", anthropic.get("content").get(0).get("type").asText());
        assertEquals("人工智能是...", anthropic.get("content").get(0).get("text").asText());
        assertEquals("qwen-max", anthropic.get("model").asText());
        assertEquals("end_turn", anthropic.get("stop_reason").asText());
        assertTrue(anthropic.get("stop_sequence").isNull());
        assertEquals(15, anthropic.get("usage").get("input_tokens").asInt());
        assertEquals(42, anthropic.get("usage").get("output_tokens").asInt());
    }

    @Test
    void response_finishReasonMapping() throws Exception {
        assertEquals("end_turn", AnthropicConverter.mapStopReason("stop"));
        assertEquals("max_tokens", AnthropicConverter.mapStopReason("length"));
        assertEquals("tool_use", AnthropicConverter.mapStopReason("tool_calls"));
        assertEquals("end_turn", AnthropicConverter.mapStopReason(null));
    }

    @Test
    void response_toolCallsArgumentsAsObject_parsedCorrectly() throws Exception {
        // Kimi 等国产上游：arguments 直接返回对象而非 JSON 字符串
        String openai = """
            {"id":"chatcmpl-x","model":"kimi-k2.6",
             "choices":[{"index":0,"finish_reason":"tool_calls",
               "message":{"role":"assistant","content":"我来帮您查询。",
                 "tool_calls":[{"id":"get_weather_0","type":"function",
                   "function":{"name":"get_weather","arguments":{"city":"北京"}}}]}}],
             "usage":{"prompt_tokens":45,"completion_tokens":172,"total_tokens":217}}
            """;
        ObjectNode anthropic = AnthropicConverter.convertResponseToAnthropic(mapper.readTree(openai), mapper);

        JsonNode toolUse = anthropic.get("content").get(1);
        assertEquals("tool_use", toolUse.get("type").asText());
        assertEquals("get_weather", toolUse.get("name").asText());
        assertEquals("北京", toolUse.get("input").get("city").asText(),
                "对象形式的 arguments 应正确转为 input，实际: " + toolUse.get("input"));
        assertEquals("tool_use", anthropic.get("stop_reason").asText());
    }

    @Test
    void response_toolCallsArgumentsAsString_parsedCorrectly() throws Exception {
        // 标准 OpenAI：arguments 是 JSON 字符串
        String openai = """
            {"id":"chatcmpl-x","model":"gpt-4",
             "choices":[{"index":0,"finish_reason":"tool_calls",
               "message":{"role":"assistant","content":null,
                 "tool_calls":[{"id":"call_1","type":"function",
                   "function":{"name":"get_weather","arguments":"{\\"city\\":\\"北京\\"}"}}]}}],
             "usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}
            """;
        ObjectNode anthropic = AnthropicConverter.convertResponseToAnthropic(mapper.readTree(openai), mapper);

        JsonNode toolUse = anthropic.get("content").get(0);
        assertEquals("北京", toolUse.get("input").get("city").asText());
    }

    // ==================== 错误转换 ====================

    @Test
    void error_openAiErrorConverted() throws Exception {
        String upstream = "{\"error\":{\"message\":\"Incorrect API key\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}";
        ObjectNode err = AnthropicConverter.convertErrorToAnthropic(upstream, "兜底", mapper);
        assertEquals("error", err.get("type").asText());
        assertEquals("invalid_request_error", err.get("error").get("type").asText());
        assertEquals("Incorrect API key", err.get("error").get("message").asText());
    }

    @Test
    void error_nonJsonUsesFallback() {
        ObjectNode err = AnthropicConverter.convertErrorToAnthropic("<html>502</html>", "上游服务错误", mapper);
        assertEquals("上游服务错误", err.get("error").get("message").asText());
    }

    // ==================== 流式事件翻译 ====================

    @Test
    void stream_fullSequence_producesCorrectEvents() {
        AnthropicStreamTranslator translator =
                new AnthropicStreamTranslator(mapper, "msg_test1", "qwen-max");

        // 首 chunk（role）
        List<AnthropicStreamTranslator.SseEvent> e1 = translator.translateChunk(
                "{\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}");
        assertTrue(e1.stream().anyMatch(e -> e.event().equals("message_start")));

        // 内容 chunk
        List<AnthropicStreamTranslator.SseEvent> e2 = translator.translateChunk(
                "{\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"春\"}}]}");
        assertTrue(e2.stream().anyMatch(e -> e.event().equals("content_block_start")));
        assertTrue(e2.stream().anyMatch(e -> e.event().equals("content_block_delta")
                && e.data().contains("春")));

        // 结束 chunk（finish_reason + usage）：仅发 content_block_stop，message_delta/message_stop 推迟到 finalize
        List<AnthropicStreamTranslator.SseEvent> e3 = translator.translateChunk(
                "{\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":86,\"total_tokens\":98}}");
        assertTrue(e3.stream().anyMatch(e -> e.event().equals("content_block_stop")));
        assertTrue(e3.stream().noneMatch(e -> e.event().equals("message_stop")));

        // finalize：发出 message_delta（含 usage）+ message_stop
        List<AnthropicStreamTranslator.SseEvent> tail = translator.finalizeIfNeeded();
        assertTrue(tail.stream().anyMatch(e -> e.event().equals("message_delta")
                && e.data().contains("end_turn") && e.data().contains("\"output_tokens\":86")));
        assertTrue(tail.stream().anyMatch(e -> e.event().equals("message_stop")));
    }

    @Test
    void stream_usageArrivesAfterFinishReason_isCapturedIntoMessageDelta() {
        // 回归：finish_reason 与 usage 分属不同 chunk 时，usage 不能被丢弃
        AnthropicStreamTranslator translator =
                new AnthropicStreamTranslator(mapper, "msg_t", "m");
        translator.translateChunk("{\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}");
        // finish_reason 先到（无 usage）
        translator.translateChunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        // usage 收尾 chunk 后到（choices 为空）
        translator.translateChunk("{\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":20,\"total_tokens\":31}}");

        List<AnthropicStreamTranslator.SseEvent> tail = translator.finalizeIfNeeded();
        String messageDelta = tail.stream()
                .filter(e -> e.event().equals("message_delta")).findFirst().orElseThrow().data();
        assertTrue(messageDelta.contains("\"output_tokens\":20"),
                "message_delta 应包含延迟到达的 output_tokens，实际: " + messageDelta);
        assertTrue(messageDelta.contains("\"input_tokens\":11"),
                "message_delta 应同时携带 input_tokens，实际: " + messageDelta);
    }

    @Test
    void stream_finishReasonMappedToMaxTokens() {
        AnthropicStreamTranslator translator =
                new AnthropicStreamTranslator(mapper, "msg_t", "m");
        translator.translateChunk("{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}");
        translator.translateChunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}");
        List<AnthropicStreamTranslator.SseEvent> events = translator.finalizeIfNeeded();
        String messageDelta = events.stream()
                .filter(e -> e.event().equals("message_delta")).findFirst().orElseThrow().data();
        assertTrue(messageDelta.contains("max_tokens"));
    }

    @Test
    void stream_finalizeWithoutFinishReason_emitsTail() {
        AnthropicStreamTranslator translator =
                new AnthropicStreamTranslator(mapper, "msg_t", "m");
        translator.translateChunk("{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}");
        List<AnthropicStreamTranslator.SseEvent> tail = translator.finalizeIfNeeded();
        assertTrue(tail.stream().anyMatch(e -> e.event().equals("message_stop")));
    }

    @Test
    void stream_messageStartContainsUsageAndModel() {
        AnthropicStreamTranslator translator =
                new AnthropicStreamTranslator(mapper, "msg_abc", "qwen-max");
        List<AnthropicStreamTranslator.SseEvent> events = translator.translateChunk(
                "{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}],"
                + "\"usage\":{\"prompt_tokens\":25,\"completion_tokens\":0}}");
        String messageStart = events.stream()
                .filter(e -> e.event().equals("message_start")).findFirst().orElseThrow().data();
        assertTrue(messageStart.contains("msg_abc"));
        assertTrue(messageStart.contains("qwen-max"));
        assertTrue(messageStart.contains("\"input_tokens\":25"));
    }
}
