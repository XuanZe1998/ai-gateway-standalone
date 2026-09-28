package org.unreal.modelrouter.common.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 协议请求 DTO 保真测试：
 * 已知字段类型化映射 + 未知字段经 @JsonAnySetter/@JsonAnyGetter 在
 * 反序列化 -> valueToTree 往返中原样保留（透传不丢字段的关键保证）。
 */
class ProtocolRequestDtoTest {

    // 与全局 JacksonConfig 一致：忽略未知属性之外的默认配置（NON_NULL 不影响本测试断言）
    private final ObjectMapper mapper = new ObjectMapper();

    // ==================== OpenAiChatRequest ====================

    @Test
    void openAi_knownFieldsMapped() throws Exception {
        String json = """
            {"model":"qwen-max","messages":[{"role":"user","content":"你好"}],
             "temperature":0.7,"max_tokens":2048,"max_completion_tokens":4096,
             "top_p":0.9,"n":1,"stream":true,"seed":42,"presence_penalty":0.5,
             "stop":["END"],"stream_options":{"include_usage":true},
             "response_format":{"type":"json_object"}}
            """;
        OpenAiChatRequest req = mapper.readValue(json, OpenAiChatRequest.class);

        assertEquals("qwen-max", req.getModel());
        assertEquals(1, req.getMessages().size());
        assertEquals("user", req.getMessages().get(0).getRole());
        assertEquals("你好", req.getMessages().get(0).getContent().asText());
        assertEquals(0.7, req.getTemperature());
        assertEquals(2048, req.getMaxTokens());
        assertEquals(4096, req.getMaxCompletionTokens());
        assertEquals(0.9, req.getTopP());
        assertEquals(1, req.getN());
        assertTrue(req.getStream());
        assertEquals(42, req.getSeed());
        assertEquals(0.5, req.getPresencePenalty());
        assertTrue(req.getStreamOptions().get("include_usage").asBoolean());
        assertEquals("json_object", req.getResponseFormat().get("type").asText());
    }

    @Test
    void openAi_unknownFieldsSurviveRoundTrip() throws Exception {
        String json = """
            {"model":"m","messages":[{"role":"user","content":"hi","custom_msg_field":"x"}],
             "frequency_penalty":0.3,"logit_bias":{"123":-100},
             "future_new_field":{"nested":[1,2,3]}}
            """;
        OpenAiChatRequest req = mapper.readValue(json, OpenAiChatRequest.class);
        ObjectNode node = mapper.valueToTree(req);

        // 顶层未知字段平铺回顶层
        assertEquals(0.3, node.get("frequency_penalty").asDouble());
        assertEquals(-100, node.get("logit_bias").get("123").asInt());
        assertEquals(3, node.get("future_new_field").get("nested").size());
        // 消息内未知字段同样保留
        assertEquals("x", node.get("messages").get(0).get("custom_msg_field").asText());
        // 已知字段蛇形命名正确
        assertTrue(node.has("model"));
    }

    @Test
    void openAi_snakeCaseSerialized() throws Exception {
        String json = """
            {"model":"m","messages":[{"role":"tool","content":"ok","tool_call_id":"call_1"}],
             "max_tokens":100,"top_p":0.5}
            """;
        OpenAiChatRequest req = mapper.readValue(json, OpenAiChatRequest.class);
        ObjectNode node = mapper.valueToTree(req);

        assertTrue(node.has("max_tokens"), "应为蛇形 max_tokens");
        assertTrue(node.has("top_p"), "应为蛇形 top_p");
        assertFalse(node.has("maxTokens"), "不应出现驼峰 maxTokens");
        assertEquals("call_1", node.get("messages").get(0).get("tool_call_id").asText());
    }

    // ==================== AnthropicMessagesRequest ====================

    @Test
    void anthropic_knownFieldsMapped() throws Exception {
        String json = """
            {"model":"claude-sonnet-4-20250514","max_tokens":1024,
             "system":"你是助手",
             "messages":[{"role":"user","content":"你好"}],
             "temperature":0.7,"top_p":0.9,"top_k":40,"stream":true,
             "stop_sequences":["END"],
             "tool_choice":{"type":"auto"},
             "thinking":{"type":"enabled","budget_tokens":8192}}
            """;
        AnthropicMessagesRequest req = mapper.readValue(json, AnthropicMessagesRequest.class);

        assertEquals("claude-sonnet-4-20250514", req.getModel());
        assertEquals(1024, req.getMaxTokens());
        assertEquals("你是助手", req.getSystem().asText());
        assertEquals(1, req.getMessages().size());
        assertEquals(0.7, req.getTemperature());
        assertEquals(0.9, req.getTopP());
        assertEquals(40, req.getTopK());
        assertTrue(req.getStream());
        assertEquals("END", req.getStopSequences().get(0));
        assertEquals("auto", req.getToolChoice().get("type").asText());
        assertEquals(8192, req.getThinking().get("budget_tokens").asInt());
    }

    @Test
    void anthropic_contentBlocksPreserved() throws Exception {
        String json = """
            {"model":"m","max_tokens":100,
             "messages":[{"role":"user","content":[
                {"type":"text","text":"看图"},
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBOR"}}
             ]}]}
            """;
        AnthropicMessagesRequest req = mapper.readValue(json, AnthropicMessagesRequest.class);
        ObjectNode node = mapper.valueToTree(req);

        JsonNode content = node.get("messages").get(0).get("content");
        assertTrue(content.isArray());
        assertEquals("text", content.get(0).get("type").asText());
        assertEquals("image", content.get(1).get("type").asText());
        assertEquals("iVBOR", content.get(1).get("source").get("data").asText());
    }

    @Test
    void anthropic_unknownFieldsSurviveRoundTrip() throws Exception {
        String json = """
            {"model":"m","max_tokens":100,
             "messages":[{"role":"user","content":"hi"}],
             "metadata":{"user_id":"u123"},"future_field":"keepme"}
            """;
        AnthropicMessagesRequest req = mapper.readValue(json, AnthropicMessagesRequest.class);
        ObjectNode node = mapper.valueToTree(req);

        assertEquals("u123", node.get("metadata").get("user_id").asText());
        assertEquals("keepme", node.get("future_field").asText());
        assertTrue(node.has("max_tokens"), "应为蛇形 max_tokens");
    }

    @Test
    void anthropic_systemArrayPreserved() throws Exception {
        String json = """
            {"model":"m","max_tokens":100,
             "system":[{"type":"text","text":"你是助手"}],
             "messages":[{"role":"user","content":"hi"}]}
            """;
        AnthropicMessagesRequest req = mapper.readValue(json, AnthropicMessagesRequest.class);
        ObjectNode node = mapper.valueToTree(req);

        assertTrue(node.get("system").isArray());
        assertEquals("你是助手", node.get("system").get(0).get("text").asText());
    }
}
