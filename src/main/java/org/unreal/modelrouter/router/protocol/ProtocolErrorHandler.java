package org.unreal.modelrouter.router.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.server.ResponseStatusException;

/**
 * 协议标准错误格式化。
 *
 * 网关自身产生的错误（参数错误 400、认证失败 401、余额不足 402、未实名 403、
 * 模型不存在 404、限流 429、超时/上游故障 5xx）按目标协议的官方错误格式返回：
 *
 *  - OpenAI：{@code {"error": {"message", "type", "code"}}}
 *  - Anthropic：{@code {"type": "error", "error": {"type", "message"}}}
 */
public final class ProtocolErrorHandler {

    private ProtocolErrorHandler() {}

    /** 从异常提取 HTTP 状态码（ResponseStatusException 取其 status，其余 500）。 */
    public static int statusOf(final Throwable t) {
        if (t instanceof ResponseStatusException rse) {
            return rse.getStatusCode().value();
        }
        if (t instanceof java.util.concurrent.TimeoutException) {
            return 504;
        }
        return 500;
    }

    /** 从异常提取错误信息（ResponseStatusException 取 reason，其余取 message）。 */
    public static String messageOf(final Throwable t) {
        if (t instanceof ResponseStatusException rse && rse.getReason() != null) {
            return rse.getReason();
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    // ==================== OpenAI 格式 ====================

    /** OpenAI 错误体：{"error": {"message", "type", "code"}}（code 按状态默认推断）。 */
    public static ObjectNode openAiError(final ObjectMapper mapper, final int status, final String message) {
        return openAiError(mapper, status, message, openAiCode(status));
    }

    /** OpenAI 错误体（显式指定 code，如认证场景保留 API_KEY_INVALID）。 */
    public static ObjectNode openAiError(final ObjectMapper mapper, final int status,
                                         final String message, final String code) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode error = mapper.createObjectNode();
        error.put("message", message);
        error.put("type", openAiType(status));
        if (code != null) {
            error.put("code", code);
        } else {
            error.putNull("code");
        }
        root.set("error", error);
        return root;
    }

    // ==================== Anthropic 格式 ====================

    /** Anthropic 错误体：{"type": "error", "error": {"type", "message"}}。 */
    public static ObjectNode anthropicError(final ObjectMapper mapper, final int status, final String message) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "error");
        ObjectNode error = mapper.createObjectNode();
        error.put("type", anthropicType(status));
        error.put("message", message);
        root.set("error", error);
        return root;
    }

    // ==================== 状态码 -> 错误类型映射 ====================

    /** OpenAI error.type。401 按接口文档样例为 invalid_request_error（code=invalid_api_key）。 */
    public static String openAiType(final int status) {
        return switch (status) {
            case 400, 401 -> "invalid_request_error";
            case 402 -> "insufficient_quota";
            case 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 429 -> "rate_limit_error";
            default -> "server_error";
        };
    }

    /** OpenAI error.code（无对应默认 code 时返回 null）。 */
    public static String openAiCode(final int status) {
        return switch (status) {
            case 401 -> "invalid_api_key";
            case 402 -> "insufficient_quota";
            case 404 -> "model_not_found";
            default -> null;
        };
    }

    /** Anthropic error.type（接口文档列举的 7 类）。 */
    public static String anthropicType(final int status) {
        return switch (status) {
            case 400 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 402, 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 429 -> "rate_limit_error";
            case 503 -> "overloaded_error";
            default -> "api_error";
        };
    }
}
