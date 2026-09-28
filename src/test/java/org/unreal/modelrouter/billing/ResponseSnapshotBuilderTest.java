package org.unreal.modelrouter.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ResponseSnapshotBuilder} 单元测试（构建/截断/脱敏/降级兜底）。
 */
class ResponseSnapshotBuilderTest {

    private ObjectMapper objectMapper;
    private ResponseSnapshotBuilder builder;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        builder = new ResponseSnapshotBuilder(objectMapper);
    }

    @Test
    void build_shouldComposeSnapshotWithUsageAndBody() {
        JsonNode usage = read("{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}");

        String snapshot = builder.build("openai", true, 200, null, null, usage,
                "{\"id\":\"chatcmpl-1\",\"choices\":[{\"finish_reason\":\"stop\"}]}");

        assertThat(snapshot).isNotNull();
        JsonNode node = read(snapshot);
        assertThat(node.path("protocol").asText()).isEqualTo("openai");
        assertThat(node.path("success").asBoolean()).isTrue();
        assertThat(node.path("httpStatus").asInt()).isEqualTo(200);
        assertThat(node.path("usage").path("total_tokens").asInt()).isEqualTo(30);
        assertThat(node.path("body").asText()).contains("chatcmpl-1");
        assertThat(node.path("bodyTruncated").asBoolean()).isFalse();
        assertThat(node.path("capturedAt").asText()).isNotBlank();
    }

    @Test
    void build_shouldTruncateOverlongBody() {
        // body 预算 4096、总长 8192：超长 body 截断且带 bodyTruncated 标记，快照总长不超上限
        String big = "x".repeat(10000);

        String snapshot = builder.build("openai", true, 200, null, null, null,
                "{\"content\":\"" + big + "\"}");

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.length()).isLessThanOrEqualTo(8192);
        JsonNode node = read(snapshot);
        assertThat(node.path("bodyTruncated").asBoolean()).isTrue();
        assertThat(node.path("body").asText().length()).isLessThanOrEqualTo(4096);
    }

    @Test
    void build_shouldStripBase64DataUrls() {
        // base64 素材脱敏为截断标记（多模态图片输出），公网 URL 原样保留可追溯
        String base64 = "iVBORw0KGgoAAAANSUhEUg==";

        String snapshot = builder.build("openai", true, 200, null, null, null,
                "{\"data\":[{\"image_url\":{\"url\":\"data:image/png;base64," + base64 + "\"}},"
                        + "{\"image_url\":{\"url\":\"https://cdn.example.com/a.png\"}}]}");

        assertThat(snapshot).isNotNull();
        assertThat(snapshot).contains("<base64 truncated, original length ");
        assertThat(snapshot).doesNotContain(base64);
        assertThat(snapshot).contains("https://cdn.example.com/a.png");
    }

    @Test
    void build_shouldReturnNull_whenAllInputsEmpty() {
        assertThat(builder.build("openai", false, null, null, null, null, null)).isNull();
        assertThat(builder.build("openai", false, null, null, null, null, "")).isNull();
    }

    @Test
    void build_shouldReturnNull_whenSerializationFails() {
        // 序列化异常降级 NULL 且不向上抛（响应缺失兜底，不阻塞计费主链路）
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.createObjectNode()).thenThrow(new IllegalStateException("serialize boom"));
        ResponseSnapshotBuilder failingBuilder = new ResponseSnapshotBuilder(failingMapper);

        assertThatCode(() -> failingBuilder.build("openai", false, 500, "500", "err", null, null))
                .doesNotThrowAnyException();
        assertThat(failingBuilder.build("openai", false, 500, "500", "err", null, null)).isNull();
    }

    @Test
    void build_shouldKeepJsonValid_whenEscapedBodyOverflowsSnapshot() {
        // body 含大量引号/反斜杠经 JSON 转义膨胀至超限：逐级缩减 body 重试后快照仍为合法 JSON
        String quoted = "\"a\\nb\"".repeat(2000);

        String snapshot = builder.build("openai", true, 200, null, null, null,
                "{\"content\":\"" + quoted + "\"}");

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.length()).isLessThanOrEqualTo(8192);
        JsonNode node = read(snapshot); // 必须仍是合法 JSON（可解析）
        assertThat(node.path("bodyTruncated").asBoolean()).isTrue();
        assertThat(node.path("capturedAt").asText()).isNotBlank();
    }

    @Test
    void build_shouldNotSplitSurrogatePair_whenTruncatingEmojiBody() {
        // 截断点落在 emoji（增补平面字符）代理对中间时回退一位，不产生孤立代理
        String content = "a" + "😀".repeat(5000);

        String snapshot = builder.build("openai", true, 200, null, null, null,
                "{\"content\":\"" + content + "\"}");

        assertThat(snapshot).isNotNull();
        String body = read(snapshot).path("body").asText();
        assertThat(body.length()).isLessThanOrEqualTo(4096);
        // 无孤立代理：每个 high surrogate 之后必须跟 low surrogate（或已到串尾）
        for (int i = 0; i < body.length() - 1; i++) {
            if (Character.isHighSurrogate(body.charAt(i))) {
                assertThat(Character.isLowSurrogate(body.charAt(i + 1))).isTrue();
            }
        }
    }

    private JsonNode read(final String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
