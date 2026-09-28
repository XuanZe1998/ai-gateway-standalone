package org.unreal.modelrouter.router.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.common.dto.AnthropicMessagesRequest;
import org.unreal.modelrouter.common.dto.OpenAiChatRequest;
import org.unreal.modelrouter.router.protocol.ProtocolPassthroughService;
import reactor.core.publisher.Mono;

/**
 * 协议纯净透传网关（OpenAI / Anthropic）
 *
 * 与 {@link UniversalController} 的区别：本控制器的接口对请求/响应做协议级透传，
 * 不包 {success,message,data,timestamp} 信封、不重建或丢弃任何字段，对外暴露
 * OpenAI / Anthropic 原生报文格式。
 *
 * 路径说明：{@code SpaWebFluxConfig.apiPathForwardFilter} 会将 {@code /v1/**}
 * 重写为 {@code /api/v1/**}，因此客户端既可走 {@code /api/v1/...} 也可走 {@code /v1/...}。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "协议透传接口", description = "OpenAI / Anthropic 原生协议纯净透传接口")
public class ProtocolGatewayController {

    private final ProtocolPassthroughService passthroughService;

    public ProtocolGatewayController(final ProtocolPassthroughService passthroughService) {
        this.passthroughService = passthroughService;
    }

    /**
     * OpenAI 协议纯净透传端点（标准路径 /v1/chat/completions）。
     *
     * 客户端调用：POST /v1/chat/completions（经 apiPathForwardFilter 重写为 /api/v1/chat/completions）
     * 认证：Authorization: Bearer &lt;your-api-key&gt;（或 X-API-Key）
     *
     * 旧信封格式端点已迁移至 /api/v1/chat/internalCompletions（内部使用）。
     */
    @Operation(summary = "OpenAI 对话补全",
            description = "OpenAI 原生协议透传。启用 stream: true 时，网关会为需要的平台自动注入 "
                    + "stream_options.include_usage: true，确保流式响应末尾包含 Token 用量。")
    @PostMapping("/chat/completions")
    public Mono<ResponseEntity<?>> openAiPassthrough(
            @RequestBody(required = false) final OpenAiChatRequest request,
            final ServerHttpRequest httpRequest) {
        return passthroughService.passthroughOpenAi(request, httpRequest);
    }

    /**
     * Anthropic 协议纯净透传端点（/v1/messages）——已下线。
     *
     * <p>暂时对外关闭，恢复时只需将 {@code @PostMapping("/messages")} 改回 {@code "/messages"} 并重新注册。
     * 底层 {@link ProtocolPassthroughService#passthroughAnthropic} 及 Anthropic 转换链路保留，未删除。
     */
    @Operation(summary = "Anthropic 对话（Messages）【已下线】",
            description = "该接口已暂时下线，不对外提供服务。恢复请联系运维。")
    // @PostMapping("/messages")  // 已下线：暂时注释映射，请求将返回 404
    public Mono<ResponseEntity<?>> anthropicMessages(
            @RequestBody(required = false) final AnthropicMessagesRequest request,
            final ServerHttpRequest httpRequest) {
        return passthroughService.passthroughAnthropic(request, httpRequest);
    }
}
