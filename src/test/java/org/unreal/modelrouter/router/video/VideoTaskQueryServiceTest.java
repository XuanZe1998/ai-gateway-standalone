package org.unreal.modelrouter.router.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.persistence.jpa.entity.VideoTaskEntity;
import org.unreal.modelrouter.persistence.jpa.repository.VideoTaskRepository;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import reactor.util.context.Context;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link VideoTaskQueryService} 单元测试（第二阶段查询链路）。
 *
 * 上游 HTTP 用 JDK 内置 HttpServer 模拟（与一阶段创建链路测试同款）；
 * 结算器 {@link VideoTaskSettler} 单独 mock，幂等计费细节见
 * {@link VideoTaskSettlerTest}。
 */
@ExtendWith(MockitoExtension.class)
class VideoTaskQueryServiceTest {

    private static final String MODEL = "doubao-seedance-2-5-251215";
    private static final String TASK_NO = "vidtask_test123";
    private static final String UPSTREAM_ID = "ark-task-123";
    private static final String INSTANCE_ID = "inst-1";

    @Mock
    private VideoTaskRepository repository;
    @Mock
    private VideoTaskUpstreamHelper upstreamHelper;
    @Mock
    private VideoTaskSettler settler;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer upstream;
    private VideoTaskQueryService service;

    /** 最近一次上游查询请求路径（用于验证 URL 拼接形态） */
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();
    /** startUpstream 创建的实例（供 upstreamHelper.resolveInstance mock 返回） */
    private ModelRouterProperties.ModelInstance lastInstance;

    @BeforeEach
    void setUp() {
        service = new VideoTaskQueryService(repository, upstreamHelper, settler, objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    // ==================== 留档缺失与归属校验 ====================

    @Test
    void queryTask_taskNotFound_returns404() {
        when(repository.findByTaskNoAndDeletedFalse(TASK_NO)).thenReturn(Optional.empty());

        ResponseEntity<?> resp = query(TASK_NO, UserIdentity.SYSTEM);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody().toString()).contains("task_not_found");
    }

    @Test
    void queryTask_otherUsersTask_returns404WithoutProbing() {
        // 归属不符与不存在统一 404，防止任务号探测
        when(repository.findByTaskNoAndDeletedFalse(TASK_NO))
                .thenReturn(Optional.of(entity("queued", "other-user")));

        ResponseEntity<?> resp = query(TASK_NO, identity("me"));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody().toString()).contains("task_not_found");
        verifyNoInteractions(upstreamHelper);
        verifyNoInteractions(settler);
    }

    // ==================== 终态直出（不再打上游） ====================

    @Test
    void queryTask_terminalSucceeded_returnsSnapshotWithoutUpstream() {
        VideoTaskEntity entity = entity("succeeded", "system");
        entity.setResponseSnapshot("{\"id\":\"" + TASK_NO + "\",\"status\":\"succeeded\","
                + "\"content\":{\"video_url\":\"https://cdn.example.com/v.mp4\"}}");
        when(repository.findByTaskNoAndDeletedFalse(TASK_NO)).thenReturn(Optional.of(entity));

        ResponseEntity<?> resp = query(TASK_NO, UserIdentity.SYSTEM);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().toString()).contains("https://cdn.example.com/v.mp4");
        // 终态直出：不打上游、不触发结算
        verifyNoInteractions(upstreamHelper);
        verifyNoInteractions(settler);
    }

    @Test
    void queryTask_terminalFallbackFirstResponse_rewritesUpstreamId() {
        // 无终态快照时回退创建响应快照，且必须改写上游任务 ID 防泄露
        VideoTaskEntity entity = entity("succeeded", "system");
        entity.setFirstResponseSnapshot("{\"id\":\"" + UPSTREAM_ID + "\",\"status\":\"queued\",\"model\":\"" + MODEL + "\"}");
        when(repository.findByTaskNoAndDeletedFalse(TASK_NO)).thenReturn(Optional.of(entity));

        ResponseEntity<?> resp = query(TASK_NO, UserIdentity.SYSTEM);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        String body = resp.getBody().toString();
        assertThat(body).contains(TASK_NO);
        assertThat(body).doesNotContain(UPSTREAM_ID);
        assertThat(body).contains("\"object\":\"video.generation.task\"");
        // status 以留档最新状态为准
        assertThat(body).contains("\"status\":\"succeeded\"");
    }

    @Test
    void queryTask_terminalNoSnapshot_synthesizesMinimalResponse() {
        // 一阶段 archiveSubmitFailed 落的提交失败记录：两类快照皆无，合成最小响应
        VideoTaskEntity entity = entity("failed", "system");
        entity.setErrorCode("504");
        entity.setErrorMessage("上游服务响应超时");
        when(repository.findByTaskNoAndDeletedFalse(TASK_NO)).thenReturn(Optional.of(entity));

        ResponseEntity<?> resp = query(TASK_NO, UserIdentity.SYSTEM);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        String body = resp.getBody().toString();
        assertThat(body).contains("\"id\":\"" + TASK_NO + "\"");
        assertThat(body).contains("\"object\":\"video.generation.task\"");
        assertThat(body).contains("\"status\":\"failed\"");
        assertThat(body).contains("\"code\":\"504\"");
        assertThat(body).contains("上游服务响应超时");
        verifyNoInteractions(upstreamHelper);
    }

    // ==================== 前置异常：缺上游任务 ID / 渠道不可用 ====================

    @Test
    void queryTask_missingUpstreamTaskId_returns502() {
        VideoTaskEntity entity = entity("queued", "system");
        entity.setUpstreamTaskId(null);
        when(repository.findByTaskNoAndDeletedFalse(TASK_NO)).thenReturn(Optional.of(entity));

        ResponseEntity<?> resp = query(TASK_NO, UserIdentity.SYSTEM);

        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        assertThat(resp.getBody().toString()).contains("upstream_task_missing");
    }

    @Test
    void queryTask_channelUnavailable_returns503() {
        when(repository.findByTaskNoAndDeletedFalse(TASK_NO))
                .thenReturn(Optional.of(entity("queued", "system")));
        when(upstreamHelper.resolveInstance(any(), any(), anyString()))
                .thenReturn(null);

        ResponseEntity<?> resp = query(TASK_NO, UserIdentity.SYSTEM);

        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        assertThat(resp.getBody().toString()).contains("upstream_unavailable");
    }

    // ==================== 上游查询：透传 + 结算 ====================

    @Test
    void queryTask_upstreamSucceeded_passThroughAndSettles() throws Exception {
        startUpstream(200, "{\"id\":\"" + UPSTREAM_ID + "\",\"task_id\":\"" + UPSTREAM_ID + "\",\"status\":\"succeeded\",\"model\":\"" + MODEL + "\","
                + "\"content\":{\"video_url\":\"https://cdn.example.com/v.mp4\"},"
                + "\"usage\":{\"completion_tokens\":1200,\"total_tokens\":1200}}");
        VideoTaskEntity entity = entity("queued", "system");
        when(upstreamHelper.resolveInstance(any(), any(), anyString()))
                .thenReturn(lastInstance);
        when(upstreamHelper.resolveQueryPath(any())).thenReturn("/api/v3/contents/generations/tasks/" + UPSTREAM_ID);
        when(repository.findByTaskNoAndDeletedFalse(TASK_NO)).thenReturn(Optional.of(entity));

        ResponseEntity<?> resp = query(TASK_NO, UserIdentity.SYSTEM);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        String body = resp.getBody().toString();
        JsonNode node = objectMapper.readTree(body);
        // 响应改写：id → 网关任务号，不泄露上游任务 ID（含 task_id 字段一并移除）
        assertThat(node.path("id").asText()).isEqualTo(TASK_NO);
        assertThat(node.path("object").asText()).isEqualTo("video.generation.task");
        assertThat(body).doesNotContain(UPSTREAM_ID);
        assertThat(node.has("task_id")).isFalse();
        assertThat(node.path("status").asText()).isEqualTo("succeeded");
        assertThat(node.path("content").path("video_url").asText()).isEqualTo("https://cdn.example.com/v.mp4");

        // 结算触发（succeeded 首次计费在 VideoTaskSettlerTest 细化验证）
        verify(settler).settle(eq(entity), any(JsonNode.class), anyString(),
                any(UserIdentity.class), isNull(), isNull());
    }

    @Test
    void queryTask_upstream404_marksExpiredAndReturns404() throws Exception {
        // 上游仅保留最近 7 天任务记录：过期后本地置 expired 止损
        startUpstream(404, "{\"error\":{\"message\":\"task not found\",\"code\":\"NotFound\"}}");
        VideoTaskEntity entity = entity("running", "system");
        when(upstreamHelper.resolveInstance(any(), any(), anyString()))
                .thenReturn(lastInstance);
        when(upstreamHelper.resolveQueryPath(any())).thenReturn("/api/v3/contents/generations/tasks/" + UPSTREAM_ID);
        when(repository.findByTaskNoAndDeletedFalse(TASK_NO)).thenReturn(Optional.of(entity));

        ResponseEntity<?> resp = query(TASK_NO, UserIdentity.SYSTEM);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody().toString()).contains("task_not_found");
        verify(settler).markExpired(eq(entity), anyString());
        verify(settler, never()).settle(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void queryTask_upstreamError_passThroughStatusWithoutSettlement() throws Exception {
        startUpstream(400, "{\"error\":{\"message\":\"Invalid task id\",\"code\":\"InvalidParameter\"}}");
        when(upstreamHelper.resolveInstance(any(), any(), anyString()))
                .thenReturn(lastInstance);
        when(upstreamHelper.resolveQueryPath(any())).thenReturn("/api/v3/contents/generations/tasks/" + UPSTREAM_ID);
        when(repository.findByTaskNoAndDeletedFalse(TASK_NO))
                .thenReturn(Optional.of(entity("queued", "system")));

        ResponseEntity<?> resp = query(TASK_NO, UserIdentity.SYSTEM);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        JsonNode body = objectMapper.readTree(resp.getBody().toString());
        assertThat(body.path("error").path("message").asText()).isEqualTo("Invalid task id");
        assertThat(body.path("error").path("code").asText()).isEqualTo("InvalidParameter");
        // 上游 4xx 不变更本地状态、不触发结算
        verify(settler, never()).settle(any(), any(), anyString(), any(), any(), any());
    }

    // ==================== 查询路径拼接（URL 形态回归，防双重拼接） ====================

    @Test
    void queryTask_blankCreationPath_fullUrlForm_appendsOnlyTaskId() throws Exception {
        // video_url 配完整 URL 形态：创建路径为空串，查询应请求 baseUrl + "/" + 上游任务 ID，
        // 若复用方舟默认查询路径会与含路径的 baseUrl 双重拼接（历史 bug 回归防护）
        startUpstream(200, "{\"id\":\"ark-task-123\",\"status\":\"queued\"}");
        when(upstreamHelper.resolveInstance(any(), any(), anyString()))
                .thenReturn(lastInstance);
        when(upstreamHelper.resolveQueryPath(any())).thenReturn("/ark-task-123");
        when(repository.findByTaskNoAndDeletedFalse(TASK_NO))
                .thenReturn(Optional.of(entity("queued", "system")));

        query(TASK_NO, UserIdentity.SYSTEM);

        assertThat(lastRequestPath.get()).isEqualTo("/ark-task-123");
    }

    @Test
    void queryTask_purePathCreationPath_appendsTaskIdToCreationPath() throws Exception {
        // video_url 纯路径形态：查询路径 = 创建路径 + "/" + 上游任务 ID
        startUpstream(200, "{\"id\":\"ark-task-123\",\"status\":\"queued\"}");
        when(upstreamHelper.resolveInstance(any(), any(), anyString()))
                .thenReturn(lastInstance);
        when(upstreamHelper.resolveQueryPath(any()))
                .thenReturn("/api/v3/contents/generations/tasks/ark-task-123");
        when(repository.findByTaskNoAndDeletedFalse(TASK_NO))
                .thenReturn(Optional.of(entity("queued", "system")));

        query(TASK_NO, UserIdentity.SYSTEM);

        assertThat(lastRequestPath.get())
                .isEqualTo("/api/v3/contents/generations/tasks/ark-task-123");
    }

    @Test
    void queryTask_telecomVendor_usesTelecomQueryPath() throws Exception {
        // 电信 aigw 协议：查询固定 GET /v1/videos/generations/task/{任务 ID}
        // （Postman 实测成功路径；/v1/videos/{id} 被 WAF 拦、创建路径拼接会 401）
        startUpstream(200, "{\"id\":\"ark-task-123\",\"status\":\"queued\"}");
        VideoTaskEntity entity = entity("queued", "system");
        entity.setVendor("telecom");
        when(upstreamHelper.resolveInstance(any(), any(), anyString()))
                .thenReturn(lastInstance);
        when(upstreamHelper.resolveQueryPath(any()))
                .thenReturn("/v1/videos/generations/task/ark-task-123");
        when(repository.findByTaskNoAndDeletedFalse(TASK_NO))
                .thenReturn(Optional.of(entity));

        query(TASK_NO, UserIdentity.SYSTEM);

        assertThat(lastRequestPath.get()).isEqualTo("/v1/videos/generations/task/ark-task-123");
    }

    // ==================== 辅助 ====================

    private static VideoTaskEntity entity(final String status, final String userId) {
        return VideoTaskEntity.builder()
                .id(1L)
                .taskNo(TASK_NO)
                .upstreamTaskId(UPSTREAM_ID)
                .status(status)
                .modelName(MODEL)
                .instanceId(INSTANCE_ID)
                .vendor("volcengine")
                .userId(userId)
                .submittedAt(LocalDateTime.now())
                .build();
    }

    private static UserIdentity identity(final String userId) {
        return new UserIdentity(userId, userId + "-acct", "key-1", "key-name",
                1L, "ent", "C001", false, 1, 7L, null);
    }

    private ResponseEntity<?> query(final String taskNo, final UserIdentity identity) {
        return service.queryTask(taskNo, null)
                .contextWrite(Context.of(UserIdentity.CONTEXT_KEY, identity))
                .block(Duration.ofSeconds(10));
    }

    private void startUpstream(final int status, final String body) throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            lastRequestPath.set(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        upstream.start();

        // 留档 instance_id 精确匹配命中（保证 API Key 与任务归属渠道一致）
        lastInstance = new ModelRouterProperties.ModelInstance();
        lastInstance.setInstanceId(INSTANCE_ID);
        lastInstance.setName("ark-test");
        lastInstance.setBaseUrl("http://127.0.0.1:" + upstream.getAddress().getPort());
        lastInstance.setVendor("volcengine");
    }
}
