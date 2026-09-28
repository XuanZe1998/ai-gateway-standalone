package org.unreal.modelrouter.router.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 协议标准错误格式化测试：状态码 -> 各协议 error.type / error.code 映射。
 */
class ProtocolErrorHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ==================== OpenAI 格式 ====================

    @Test
    void openAi_400_invalidRequest() {
        JsonNode body = ProtocolErrorHandler.openAiError(mapper, 400, "model is required");
        JsonNode error = body.get("error");
        assertEquals("model is required", error.get("message").asText());
        assertEquals("invalid_request_error", error.get("type").asText());
        assertTrue(error.get("code").isNull());
    }

    @Test
    void openAi_401_matchesDocExample() {
        // 接口文档样例：{"error":{"message":"Incorrect API key provided","type":"invalid_request_error","code":"invalid_api_key"}}
        JsonNode body = ProtocolErrorHandler.openAiError(mapper, 401, "Incorrect API key provided");
        JsonNode error = body.get("error");
        assertEquals("invalid_request_error", error.get("type").asText());
        assertEquals("invalid_api_key", error.get("code").asText());
    }

    @Test
    void openAi_402_insufficientQuota() {
        JsonNode error = ProtocolErrorHandler.openAiError(mapper, 402, "账户余额不足，请充值。").get("error");
        assertEquals("insufficient_quota", error.get("type").asText());
        assertEquals("insufficient_quota", error.get("code").asText());
        assertEquals("账户余额不足，请充值。", error.get("message").asText());
    }

    @Test
    void openAi_statusMapping() {
        assertEquals("permission_error", ProtocolErrorHandler.openAiType(403));
        assertEquals("not_found_error", ProtocolErrorHandler.openAiType(404));
        assertEquals("rate_limit_error", ProtocolErrorHandler.openAiType(429));
        assertEquals("server_error", ProtocolErrorHandler.openAiType(500));
        assertEquals("server_error", ProtocolErrorHandler.openAiType(504));
    }

    @Test
    void openAi_explicitCodeOverridesDefault() {
        JsonNode error = ProtocolErrorHandler.openAiError(mapper, 401, "无效的API Key", "API_KEY_INVALID").get("error");
        assertEquals("API_KEY_INVALID", error.get("code").asText());
    }

    // ==================== Anthropic 格式 ====================

    @Test
    void anthropic_400_matchesDocExample() {
        // 接口文档样例：{"type":"error","error":{"type":"invalid_request_error","message":"model is required"}}
        JsonNode body = ProtocolErrorHandler.anthropicError(mapper, 400, "model is required");
        assertEquals("error", body.get("type").asText());
        assertEquals("invalid_request_error", body.get("error").get("type").asText());
        assertEquals("model is required", body.get("error").get("message").asText());
    }

    @Test
    void anthropic_statusMapping() {
        assertEquals("authentication_error", ProtocolErrorHandler.anthropicType(401));
        assertEquals("permission_error", ProtocolErrorHandler.anthropicType(402));
        assertEquals("permission_error", ProtocolErrorHandler.anthropicType(403));
        assertEquals("not_found_error", ProtocolErrorHandler.anthropicType(404));
        assertEquals("rate_limit_error", ProtocolErrorHandler.anthropicType(429));
        assertEquals("api_error", ProtocolErrorHandler.anthropicType(500));
        assertEquals("api_error", ProtocolErrorHandler.anthropicType(504));
        assertEquals("overloaded_error", ProtocolErrorHandler.anthropicType(503));
    }

    // ==================== 异常提取 ====================

    @Test
    void statusOf_responseStatusException() {
        var rse = new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "账户余额不足");
        assertEquals(402, ProtocolErrorHandler.statusOf(rse));
        assertEquals("账户余额不足", ProtocolErrorHandler.messageOf(rse));
    }

    @Test
    void statusOf_timeoutIs504() {
        assertEquals(504, ProtocolErrorHandler.statusOf(new java.util.concurrent.TimeoutException("timeout")));
    }

    @Test
    void statusOf_genericIs500() {
        assertEquals(500, ProtocolErrorHandler.statusOf(new RuntimeException("boom")));
        assertEquals("boom", ProtocolErrorHandler.messageOf(new RuntimeException("boom")));
    }
}
