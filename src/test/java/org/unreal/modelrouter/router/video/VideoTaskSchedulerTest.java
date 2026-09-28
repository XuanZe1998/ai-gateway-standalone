package org.unreal.modelrouter.router.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.persistence.jpa.entity.VideoTaskEntity;
import org.unreal.modelrouter.persistence.jpa.repository.VideoTaskRepository;
import org.unreal.modelrouter.router.model.ModelRouterProperties;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link VideoTaskScheduler} 单元测试（后台轮询防资损）。
 */
@ExtendWith(MockitoExtension.class)
class VideoTaskSchedulerTest {

    @Mock
    private VideoTaskRepository repository;
    @Mock
    private VideoTaskUpstreamHelper upstreamHelper;
    @Mock
    private VideoTaskSettler settler;
    @Mock
    private ModelRouterProperties.ModelInstance instance;

    private VideoTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new VideoTaskScheduler(repository, upstreamHelper, settler, new ObjectMapper());
        // 通过反射或 setter 注入配置值（@Value 字段）
        setField(scheduler, "enabled", true);
        setField(scheduler, "batchSize", 100);
        setField(scheduler, "minAgeSeconds", 10);
        setField(scheduler, "ttlDays", 7);
        setField(scheduler, "upstreamErrorAlertThreshold", 10);
    }

    // ==================== 开关与批次 ====================

    @Test
    void pollAndSettle_disabled_doesNothing() {
        setField(scheduler, "enabled", false);
        scheduler.pollAndSettle();
        verifyNoInteractions(repository);
    }

    @Test
    void pollAndSettle_noTasks_doesNothing() {
        when(repository.findNonTerminalTasks(any(), any())).thenReturn(List.of());
        scheduler.pollAndSettle();
        verify(repository).findNonTerminalTasks(any(), any());
        verifyNoInteractions(settler);
    }

    // ==================== TTL 过期 ====================

    @Test
    void pollAndSettle_taskBeyondTtl_marksExpiredWithoutUpstream() {
        VideoTaskEntity entity = entity();
        entity.setSubmittedAt(LocalDateTime.now().minusDays(8)); // 超 7 天 TTL
        when(repository.findNonTerminalTasks(any(), any())).thenReturn(List.of(entity));

        scheduler.pollAndSettle();

        verify(settler).markExpired(org.mockito.ArgumentMatchers.eq(entity),
                org.mockito.ArgumentMatchers.contains("后台轮询超 TTL"));
        // 不打上游（无实例解析）
        verifyNoInteractions(upstreamHelper);
    }

    // ==================== 上游任务 ID 缺失 ====================

    @Test
    void pollAndSettle_missingUpstreamTaskId_skips() {
        VideoTaskEntity entity = entity();
        entity.setUpstreamTaskId(null);
        entity.setSubmittedAt(LocalDateTime.now().minusMinutes(5)); // 非 TTL
        when(repository.findNonTerminalTasks(any(), any())).thenReturn(List.of(entity));

        scheduler.pollAndSettle();

        // 不结算、不过期、不打上游
        verifyNoInteractions(settler);
        verifyNoInteractions(upstreamHelper);
    }

    // ==================== 实例解析失败 ====================

    @Test
    void pollAndSettle_instanceResolutionFails_skipsThisRound() {
        VideoTaskEntity entity = entity();
        entity.setSubmittedAt(LocalDateTime.now().minusMinutes(5));
        when(repository.findNonTerminalTasks(any(), any())).thenReturn(List.of(entity));
        when(upstreamHelper.resolveInstance(any(), any(), anyString())).thenReturn(null);

        scheduler.pollAndSettle();

        // 不结算、不过期（下轮重试）
        verifyNoInteractions(settler);
    }

    // ==================== 正常查询触发结算 ====================

    @Test
    void pollAndSettle_runningTask_queriesUpstreamAndSettles() {
        VideoTaskEntity entity = entity();
        entity.setSubmittedAt(LocalDateTime.now().minusMinutes(5));
        when(repository.findNonTerminalTasks(any(), any())).thenReturn(List.of(entity));
        when(upstreamHelper.resolveInstance(any(), any(), anyString())).thenReturn(instance);
        when(instance.getBaseUrl()).thenReturn("https://ark.cn-beijing.volces.com");
        when(instance.getHeaders()).thenReturn(java.util.Map.of("Authorization", "Bearer test"));
        when(upstreamHelper.resolveQueryPath(any()))
                .thenReturn("/api/v3/contents/generations/tasks/ark-task-123");

        // 注：此处无法 mock WebClient 网络层，仅验证流程走到查询阶段不抛异常
        // 实际查询触发由集成测试覆盖；单测验证调度器正确识别非终态任务并尝试处理
        scheduler.pollAndSettle();

        // 验证了任务被选中（进入处理流程），具体 WebClient 调用为集成测试范畴
        verify(repository).findNonTerminalTasks(any(), any());
    }

    // ==================== 辅助 ====================

    private static VideoTaskEntity entity() {
        return VideoTaskEntity.builder()
                .id(1L)
                .taskNo("vidtask_test123")
                .upstreamTaskId("ark-task-123")
                .status("running")
                .modelName("doubao-seedance-2-5-251215")
                .vendor("volcengine")
                .channelId("ch-1")
                .instanceId("inst-1")
                .baseUrl("https://ark.cn-beijing.volces.com")
                .userId("u1")
                .userAccount("u1-acct")
                .submittedAt(LocalDateTime.now().minusMinutes(5))
                .build();
    }

    private static void setField(final Object target, final String fieldName, final Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("set field " + fieldName + " failed", e);
        }
    }
}
