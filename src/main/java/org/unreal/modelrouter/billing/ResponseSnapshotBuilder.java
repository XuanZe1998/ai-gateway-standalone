package org.unreal.modelrouter.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 计费账单「模型返回响应快照」构建器。
 *
 * <p>输入上游返回素材（协议/状态/错误/用量/响应体），输出 ≤8KB 的 JSON 快照；
 * 任何异常只 WARN 并降级返回 NULL，绝不向上抛——快照失败不得影响计费主链路
 * （与「计费失败不阻塞主链路」原则一致）。
 *
 * <p>脱敏：响应体中的 base64 data URL（多模态图片输出等）递归替换为截断标记，
 * 公网 URL 原样保留（对齐 {@code VideoTaskArchiver} 请求快照的处理方式）。
 */
@Component
public class ResponseSnapshotBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResponseSnapshotBuilder.class);

    /** 快照总长度上限（字符），对齐 VideoTaskArchiver.MAX_RESPONSE_SNAPSHOT 的 8KB 惯例 */
    private static final int MAX_SNAPSHOT = 8192;
    /** body 预算（字符），为 status/usage/error 等元数据预留空间 */
    private static final int MAX_BODY = 4096;
    /** errorMessage 截断上限（字符），与账单 error_message 列宽口径一致 */
    private static final int MAX_ERROR_MESSAGE = 500;
    /** base64 素材脱敏标记 */
    private static final String BASE64_MARK = "<base64 truncated, original length %d>";

    private final ObjectMapper objectMapper;

    public ResponseSnapshotBuilder(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 构建响应快照 JSON。
     *
     * @param protocol     协议/服务标识（openai/anthropic/vidGen 等）
     * @param success      调用是否成功
     * @param httpStatus   上游 HTTP 状态码（可空）
     * @param errorCode    错误码（可空）
     * @param errorMessage 错误信息（可空，截断至 500）
     * @param usage        用量原始结构（可空）
     * @param body         响应体原文（可空；base64 脱敏 + 截断）
     * @return ≤8KB 的快照 JSON；素材全空或构建异常时返回 null
     */
    public String build(final String protocol, final boolean success, final Integer httpStatus,
                        final String errorCode, final String errorMessage,
                        final JsonNode usage, final String body) {
        // 空白 body 视为无响应体（上游空响应/空 chunk），不落半空快照
        String effectiveBody = body != null && !body.isBlank() ? body : null;
        if (usage == null && effectiveBody == null && errorCode == null && errorMessage == null) {
            return null; // 全空素材不落无意义行
        }
        try {
            ObjectNode snapshot = objectMapper.createObjectNode();
            snapshot.put("protocol", protocol);
            snapshot.put("success", success);
            if (httpStatus != null) {
                snapshot.put("httpStatus", httpStatus);
            }
            if (errorCode != null) {
                snapshot.put("errorCode", errorCode);
            }
            if (errorMessage != null) {
                snapshot.put("errorMessage", truncate(errorMessage, MAX_ERROR_MESSAGE));
            }
            snapshot.set("usage", usage != null ? usage : objectMapper.nullNode());
            String sanitized = sanitizeBody(effectiveBody);
            if (sanitized != null) {
                snapshot.put("bodyTruncated", sanitized.length() > MAX_BODY);
                snapshot.put("body", truncate(sanitized, MAX_BODY));
            }
            snapshot.put("capturedAt", LocalDateTime.now().toString());
            String json = objectMapper.writeValueAsString(snapshot);
            if (json.length() <= MAX_SNAPSHOT) {
                return json;
            }
            // 总长超限（body 转义膨胀所致）：按码点逐级缩减 body 重试，保证快照恒为合法 JSON，
            // bodyTruncated/capturedAt 等元数据字段不被截断
            if (snapshot.has("body")) {
                String bodyText = snapshot.path("body").asText();
                int budget = bodyText.codePointCount(0, bodyText.length());
                while (budget > 0) {
                    budget = budget / 2;
                    snapshot.put("body", truncate(bodyText, budget));
                    snapshot.put("bodyTruncated", true);
                    json = objectMapper.writeValueAsString(snapshot);
                    if (json.length() <= MAX_SNAPSHOT) {
                        return json;
                    }
                }
            }
            // 元数据自身超限（usage/error 异常巨大）的最终兜底：按码点边界截断（不再保证 JSON 合法，但不劈开代理对）
            return truncate(json, MAX_SNAPSHOT);
        } catch (Exception e) {
            LOGGER.warn("计费响应快照构建失败，降级为 NULL: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 响应体脱敏：可解析的 JSON 递归替换 base64 data URL 后重新序列化；
     * 解析失败（非 JSON 文本，如 WAF 挑战页）原样返回交由截断处理。
     */
    private String sanitizeBody(final String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode copy = node.deepCopy();
            stripBase64Data(copy);
            return objectMapper.writeValueAsString(copy);
        } catch (Exception e) {
            return body; // 非 JSON 文本原样保留（后续按长度截断）
        }
    }

    /**
     * 递归替换所有 base64 data URL 为截断标记（对齐 VideoTaskArchiver 请求快照处理）：
     * 公网 URL 类引用原样保留，保证追溯时仍可定位素材来源。
     */
    private void stripBase64Data(final JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                JsonNode value = node.get(field);
                if (value.isTextual() && isBase64DataUrl(value.asText())) {
                    String text = value.asText();
                    int comma = text.indexOf(',');
                    String prefix = comma >= 0 ? text.substring(0, comma + 1) : "data:";
                    ((ObjectNode) node).put(field, prefix + String.format(BASE64_MARK, text.length()));
                } else {
                    stripBase64Data(value);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                stripBase64Data(child);
            }
        }
    }

    private static boolean isBase64DataUrl(final String text) {
        return text != null && text.startsWith("data:") && text.contains(";base64,");
    }

    /**
     * 截断至 max 个 UTF-16 字符；截断点若落在代理对中间（emoji 等增补平面字符）则回退一位，
     * 避免产生孤立代理导致写库被替换为 '?' 或触发严格 UTF-8 校验失败。
     */
    private static String truncate(final String text, final int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        int end = max;
        if (Character.isHighSurrogate(text.charAt(end - 1))
                && end < text.length() && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end);
    }
}
