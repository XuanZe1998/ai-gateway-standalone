package org.unreal.modelrouter.router.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.unreal.modelrouter.common.dto.VideoGenerationRequest;
import org.unreal.modelrouter.router.video.VideoTaskQueryService;
import org.unreal.modelrouter.router.video.VideoTaskService;
import reactor.core.publisher.Mono;

/**
 * 视频生成任务接口（创建 + 查询）。
 *
 * 视频生成为异步任务：创建端点提交任务后立即返回网关任务号，
 * 任务结果通过查询端点（GET /v1/videos/generations/task/{taskId}）轮询获取。
 *
 * 响应为 OpenAI 资源对象风格：
 * {@code {"id": "vidtask_xxx", "object": "video.generation.task", "created_at": <unix秒>,
 *         "model": "...", "status": "queued"}}；对外仅暴露网关任务号。
 * 错误统一 OpenAI 格式 {@code {"error": {"message", "type", "code"}}}。
 *
 * 路径说明：{@code SpaWebFluxConfig.apiPathForwardFilter} 会将 {@code /v1/**}
 * 重写为 {@code /api/v1/**}，因此客户端既可走 {@code /api/v1/...} 也可走 {@code /v1/...}。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "视频生成接口", description = "视频生成异步任务接口（OpenAI 风格协议）")
public class VideoGenerationController {

    private final VideoTaskService videoTaskService;
    private final VideoTaskQueryService videoTaskQueryService;

    public VideoGenerationController(final VideoTaskService videoTaskService,
                                     final VideoTaskQueryService videoTaskQueryService) {
        this.videoTaskService = videoTaskService;
        this.videoTaskQueryService = videoTaskQueryService;
    }

    /**
     * 创建视频生成任务（标准路径 /v1/videos/generations）。
     *
     * 客户端调用：POST /v1/videos/generations（经 apiPathForwardFilter 重写为 /api/v1/videos/generations）
     * 认证：Authorization: Bearer &lt;your-api-key&gt;（或 X-API-Key）
     *
     * 计费策略：创建不计费（业界主流），查询链路首次观测到 succeeded 时按实际用量结算。
     */
    @Operation(summary = "创建视频生成任务",
            description = "异步提交视频生成任务，立即返回网关任务号；请求字段参照方舟 Seedance 协议全量透传。")
    @PostMapping("/videos/generations")
    public Mono<ResponseEntity<?>> createVideoGenerationTask(
            @RequestBody(required = false) final VideoGenerationRequest request,
            final ServerWebExchange exchange) {
        return videoTaskService.createTask(request, exchange);
    }

    /**
     * 查询视频生成任务（标准路径 /v1/videos/generations/task/{taskId}）。
     *
     * 客户端调用：GET /v1/videos/generations/task/{taskId}
     * （经 apiPathForwardFilter 重写为 /api/v1/videos/generations/task/{taskId}）
     * 认证：Authorization: Bearer &lt;your-api-key&gt;（或 X-API-Key），仅任务创建人可查
     *
     * 计费策略：查询免费；首次观测到 succeeded 时按上游实际用量（输出分辨率 + 时长秒数
     * + 有无视频输入）走视频计费（serviceType=vidGen，价格模式 + 计费单位 + 分辨率规则）结算，
     * 并发轮询下仅结算一次。
     */
    @Operation(summary = "查询视频生成任务",
            description = "按网关任务号轮询任务状态与结果；响应为改写后的上游任务资源对象（id 为网关任务号）。"
                    + "首次观测到 succeeded 时自动完成计费结算。")
    @GetMapping("/videos/generations/task/{taskId}")
    public Mono<ResponseEntity<?>> queryVideoGenerationTask(
            @PathVariable("taskId") final String taskNo,
            final ServerWebExchange exchange) {
        return videoTaskQueryService.queryTask(taskNo, exchange);
    }

    /**
     * 查询视频生成任务（taskId 缺失兜底）。
     *
     * 路径模式 {@code /videos/generations/task/{taskId}} 要求 taskId 段非空，
     * 未携带 taskId 的请求（含尾斜杠）若不兜底会落入静态资源处理返回
     * 404 No static resource；此处显式映射并按参数缺失返回 400。
     */
    @GetMapping({"/videos/generations/task", "/videos/generations/task/"})
    public Mono<ResponseEntity<?>> queryVideoGenerationTaskMissingTaskId(
            final ServerWebExchange exchange) {
        return videoTaskQueryService.queryTask(null, exchange);
    }
}
