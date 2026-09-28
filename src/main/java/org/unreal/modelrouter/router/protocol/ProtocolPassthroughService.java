package org.unreal.modelrouter.router.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.billing.BalanceCheckService;
import org.unreal.modelrouter.billing.BillingService;
import org.unreal.modelrouter.billing.freequota.FreeQuotaResult;
import org.unreal.modelrouter.billing.freequota.FreeQuotaService;
import org.unreal.modelrouter.common.util.ApplicationContextProvider;
import org.unreal.modelrouter.common.util.IpUtils;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 协议纯净透传服务（OpenAI / Anthropic）
 *
 * 与既有 BaseAdapter 流水线的区别：
 *  - 请求体逐字节透传给上游，不重建、不丢弃任何字段（tools / seed / response_format /
 *    content-block 数组等全部原样保留）；
 *  - 非流式响应原样返回上游 JSON，不包 {success,message,data,timestamp} 信封；
 *  - 流式响应逐 chunk 透传（OpenAI chat.completion.chunk 或 Anthropic message_start /
 *    content_block_delta 等事件），不重排事件。
 *
 * 认证（客户端→网关）沿用现有 API Key / JWT 过滤器；下游认证取实例级 headers.Authorization。
 * 计费与既有链路一致：按 usage（OpenAI 的 prompt/completion/total_tokens 或
 * Anthropic 的 input/output_tokens）记录，命中免费额度则折抵。
 */
@Service
public class ProtocolPassthroughService {

    private static final Logger logger = LoggerFactory.getLogger(ProtocolPassthroughService.class);

    /** 协议标识：OpenAI */
    public static final String PROTOCOL_OPENAI = "openai";
    /** 协议标识：Anthropic */
    public static final String PROTOCOL_ANTHROPIC = "anthropic";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private final ModelServiceRegistry registry;
    private final BalanceCheckService balanceCheckService;
    private final ObjectMapper objectMapper;
    private final org.unreal.modelrouter.billing.ResponseSnapshotBuilder responseSnapshotBuilder;

    public ProtocolPassthroughService(final ModelServiceRegistry registry,
                                      final BalanceCheckService balanceCheckService,
                                      final ObjectMapper objectMapper,
                                      final org.unreal.modelrouter.billing.ResponseSnapshotBuilder responseSnapshotBuilder) {
        this.registry = registry;
        this.balanceCheckService = balanceCheckService;
        this.objectMapper = objectMapper;
        this.responseSnapshotBuilder = responseSnapshotBuilder;
    }

    /**
     * OpenAI 协议透传（/v1/chat/completions）。
     *
     * @param request     类型化请求 DTO（未知字段经 AnySetter 保留，valueToTree 后全量透传）
     * @param httpRequest 当前 HTTP 请求（用于提取客户端 IP）
     * @return 透传响应（流式为 SSE，非流式为原生 JSON）
     */
    public Mono<ResponseEntity<?>> passthroughOpenAi(
            final org.unreal.modelrouter.common.dto.OpenAiChatRequest request,
            final org.springframework.http.server.reactive.ServerHttpRequest httpRequest) {
        if (request == null) {
            return Mono.just(protocolErrorEntity(PROTOCOL_OPENAI, 400, "Request body is required"));
        }
        return passthrough(PROTOCOL_OPENAI, objectMapper.valueToTree(request), httpRequest)
                .onErrorResume(t -> Mono.just(protocolErrorEntity(PROTOCOL_OPENAI, t)));
    }

    /**
     * Anthropic 协议透传（/v1/messages）。请求先转换为 OpenAI 格式再发上游。
     *
     * @param request     类型化请求 DTO（未知字段经 AnySetter 保留）
     * @param httpRequest 当前 HTTP 请求（用于提取客户端 IP）
     * @return 透传响应（流式为 Anthropic SSE 事件，非流式为 Anthropic message JSON）
     */
    public Mono<ResponseEntity<?>> passthroughAnthropic(
            final org.unreal.modelrouter.common.dto.AnthropicMessagesRequest request,
            final org.springframework.http.server.reactive.ServerHttpRequest httpRequest) {
        if (request == null) {
            return Mono.just(protocolErrorEntity(PROTOCOL_ANTHROPIC, 400, "Request body is required"));
        }
        return passthrough(PROTOCOL_ANTHROPIC, objectMapper.valueToTree(request), httpRequest)
                .onErrorResume(t -> Mono.just(protocolErrorEntity(PROTOCOL_ANTHROPIC, t)));
    }

    /**
     * 网关自身错误（400/402/403/404/429/5xx 等）按目标协议格式化返回。
     */
    private ResponseEntity<?> protocolErrorEntity(final String protocol, final Throwable t) {
        return protocolErrorEntity(protocol, ProtocolErrorHandler.statusOf(t), ProtocolErrorHandler.messageOf(t));
    }

    private ResponseEntity<?> protocolErrorEntity(final String protocol, final int status, final String message) {
        var body = PROTOCOL_ANTHROPIC.equals(protocol)
                ? ProtocolErrorHandler.anthropicError(objectMapper, status, message)
                : ProtocolErrorHandler.openAiError(objectMapper, status, message);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body.toString());
    }

    /**
     * 透传一次对话请求（协议内部统一入口）。
     */
    private Mono<ResponseEntity<?>> passthrough(final String protocol,
                                               final com.fasterxml.jackson.databind.node.ObjectNode requestNode,
                                               final org.springframework.http.server.reactive.ServerHttpRequest httpRequest) {
        String model = requestNode.path("model").asText(null);
        if (model == null || model.isBlank()) {
            return Mono.error(new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "model is required"));
        }
        boolean stream = requestNode.path("stream").asBoolean(false);

        // Anthropic：请求体先转换为 OpenAI 格式（上游均为 OpenAI 兼容协议）
        final JsonNode upstreamRequestNode = PROTOCOL_ANTHROPIC.equals(protocol)
                ? AnthropicConverter.convertRequestToOpenAI(requestNode, objectMapper)
                : requestNode;

        final String clientIp = httpRequest != null ? IpUtils.getClientIp(httpRequest) : null;
        final ModelRouterProperties.ModelInstance instance;
        try {
            instance = registry.selectInstance(ModelServiceRegistry.ServiceType.chat, model, clientIp);
        } catch (Exception e) {
            logger.error("透传实例选择失败: protocol={}, model={}", protocol, model, e);
            return Mono.error(e);
        }

        final String path = registry.getModelPath(ModelServiceRegistry.ServiceType.chat, model);
        final WebClient client = buildWebClient(instance);
        final long startTime = System.currentTimeMillis();

        return Mono.deferContextual(ctx -> {
            UserIdentity identity = ctx.getOrDefault(UserIdentity.CONTEXT_KEY, UserIdentity.SYSTEM);
            return balanceCheckService.checkBalance(identity, ModelServiceRegistry.ServiceType.chat.name())
                    .then(stream
                            ? doStreaming(protocol, upstreamRequestNode, client, path, instance, model, startTime, identity)
                            : doNonStreaming(protocol, upstreamRequestNode, client, path, instance, model, startTime, identity));
        });
    }

    // ==================== 非流式 ====================

    private Mono<ResponseEntity<?>> doNonStreaming(final String protocol,
                                                   final JsonNode upstreamRequestNode,
                                                   final WebClient client,
                                                   final String path,
                                                   final ModelRouterProperties.ModelInstance instance,
                                                   final String model,
                                                   final long startTime,
                                                   final UserIdentity identity) {
        WebClient.RequestBodySpec spec = buildRequestSpec(client, path, instance);

        // 使用 exchangeToMono 而非 retrieve：上游 4xx/5xx 的原生错误体也透传（按各自协议格式化），
        // 不抛 WebClientResponseException 走全局信封异常处理，保证错误响应同样协议纯净。
        return spec.bodyValue(upstreamRequestNode)
                .exchangeToMono(resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .<ResponseEntity<?>>map(body -> {
                            long duration = System.currentTimeMillis() - startTime;
                            int status = resp.statusCode().value();
                            boolean success = resp.statusCode().is2xxSuccessful();

                            org.unreal.modelrouter.billing.usage.TokenUsage usage = success ? extractUsage(body, instance) : null;
                            recordBilling(protocol, instance, model, duration, success,
                                    success ? null : String.valueOf(status), success ? null : truncate(body),
                                    usage, identity, status, body);

                            String responseBody = formatUpstreamBody(protocol, body, success);
                            return ResponseEntity
                                    .status(resp.statusCode())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body(responseBody);
                        }))
                .timeout(getTimeout(instance))
                .onErrorResume(TimeoutException.class, e -> {
                    logger.error("透传非流式请求超时: protocol={}, model={}", protocol, model);
                    recordBilling(protocol, instance, model, System.currentTimeMillis() - startTime,
                            false, "504", "上游服务响应超时", null, identity, 504, null);
                    return Mono.error(new ResponseStatusException(
                            org.springframework.http.HttpStatus.GATEWAY_TIMEOUT, "上游服务响应超时，请稍后重试"));
                });
    }

    /**
     * 按目标协议格式化上游响应体。
     * OpenAI：原样透传；Anthropic：成功响应转 Anthropic message，错误响应转 Anthropic error。
     */
    private String formatUpstreamBody(final String protocol, final String body, final boolean success) {
        if (!PROTOCOL_ANTHROPIC.equals(protocol)) {
            return body;
        }
        try {
            if (success) {
                JsonNode openaiResp = objectMapper.readTree(body);
                return AnthropicConverter.convertResponseToAnthropic(openaiResp, objectMapper).toString();
            }
            return AnthropicConverter.convertErrorToAnthropic(body, "上游服务错误", objectMapper).toString();
        } catch (Exception e) {
            logger.warn("Anthropic 响应转换失败，原样返回: {}", e.getMessage());
            return body;
        }
    }

    // ==================== 流式 ====================

    private Mono<ResponseEntity<?>> doStreaming(final String protocol,
                                                final JsonNode upstreamRequestNode,
                                                final WebClient client,
                                                final String path,
                                                final ModelRouterProperties.ModelInstance instance,
                                                final String model,
                                                final long startTime,
                                                final UserIdentity identity) {
        WebClient.RequestBodySpec spec = buildRequestSpec(client, path, instance);

        // 用于从 SSE chunk 中捕获 usage（OpenAI 格式）
        AtomicReference<org.unreal.modelrouter.billing.usage.TokenUsage> capturedUsage = new AtomicReference<>(null);
        // 携带 usage 的尾部 chunk（含 finish_reason 与 choices 收尾），作为计费响应快照的 body
        AtomicReference<String> lastUsageChunk = new AtomicReference<>(null);
        AtomicBoolean billingRecorded = new AtomicBoolean(false);

        // OpenAI 协议：按需注入 stream_options.include_usage（复用现有配置，如火山方舟）；
        // Anthropic 协议：必须注入以拿到流式 usage（用于计费 + message_start/message_delta 的 usage 字段）。
        Object bodyToSend = PROTOCOL_ANTHROPIC.equals(protocol)
                ? forceStreamOptionsIncludeUsage(upstreamRequestNode)
                : injectStreamOptionsIfNeeded(upstreamRequestNode, instance);

        // Anthropic：有状态流式翻译器（OpenAI chunk -> Anthropic SSE 事件）
        final AnthropicStreamTranslator translator = PROTOCOL_ANTHROPIC.equals(protocol)
                ? new AnthropicStreamTranslator(objectMapper, "msg_" + java.util.UUID.randomUUID().toString().replace("-", ""), model)
                : null;

        // === 计费修复（方向B：解耦上游消费与客户端连接）===
        // 背景：与 StreamingRequestProcessor 相同——上游 usage chunk 在流末尾才发送，
        //   旧实现把上游 Flux 直接作为响应体返回，客户端断连的 broken pipe 以 onError 杀掉上游订阅，
        //   导致 usage chunk 读不到、计费丢失。
        // 解法：用 Sinks.Many 桥接——上游独立订阅、读完整条流（含末尾 usage）后再计费；
        //   客户端只订阅 sink，其断连不再反传到上游，故 usage 一定能捕获到。
        final Sinks.Many<ServerSentEvent<String>> clientSink =
                Sinks.many().unicast().onBackpressureBuffer();
        final Duration upstreamTimeout = getTimeout(instance);

        // 上游消费链：独立订阅，客户端取消不会中断它（这是修复的核心）
        spec
                .bodyValue(bodyToSend)
                .retrieve()
                .onStatus(org.springframework.http.HttpStatusCode::isError, resp ->
                        resp.bodyToMono(String.class).defaultIfEmpty("").flatMap(errBody -> {
                            logger.error("透传流式上游错误: protocol={}, instance={}, status={}",
                                    protocol, instance.getName(), resp.statusCode());
                            // 流式上游在建立连接阶段就失败：按目标协议返回错误体（非 SSE）
                            return Mono.error(new UpstreamHttpException(
                                    resp.statusCode().value(), formatUpstreamBody(protocol, errBody, false)));
                        }))
                .bodyToFlux(String.class)
                .timeout(upstreamTimeout)
                .concatMap(chunk -> {
                    captureUsageFromChunk(chunk, capturedUsage, lastUsageChunk, instance);
                    if (PROTOCOL_ANTHROPIC.equals(protocol)) {
                        // OpenAI chunk -> 0..N 个 Anthropic SSE 事件
                        List<AnthropicStreamTranslator.SseEvent> events = translator.translateChunk(chunk);
                        return Flux.fromIterable(events).map(e -> ServerSentEvent.<String>builder()
                                .event(e.event()).data(e.data()).build());
                    }
                    // OpenAI：逐 chunk 透传
                    return Flux.just(ServerSentEvent.<String>builder().data(chunk).build());
                })
                .concatWith(Flux.defer(() -> {
                    // Anthropic：上游正常结束但未发 finish_reason 时兜底收尾
                    if (PROTOCOL_ANTHROPIC.equals(protocol)) {
                        List<AnthropicStreamTranslator.SseEvent> tail = translator.finalizeIfNeeded();
                        return Flux.fromIterable(tail).map(e -> ServerSentEvent.<String>builder()
                                .event(e.event()).data(e.data()).build());
                    }
                    return Flux.empty();
                }))
                .doOnNext(sse -> {
                    // 转发给客户端；客户端已断连时 tryEmitNext 返回 FAIL_CANCELLED，静默忽略
                    clientSink.tryEmitNext(sse);
                })
                .doOnComplete(() -> {
                    clientSink.tryEmitComplete();
                    // 上游读完整条流，usage 已捕获，按实际用量计费
                    if (billingRecorded.compareAndSet(false, true)) {
                        recordBilling(protocol, instance, model, System.currentTimeMillis() - startTime,
                                capturedUsage.get() != null, null, null, capturedUsage.get(), identity,
                                200, lastUsageChunk.get());
                    }
                })
                .doOnError(throwable -> {
                    // 上游真实错误（客户端断连已不再传播到此处）：
                    //   已捕获 usage 则按用量计费，否则落失败账单
                    clientSink.tryEmitError(throwable);
                    if (billingRecorded.compareAndSet(false, true)) {
                        String code = throwable instanceof ResponseStatusException rse
                                ? String.valueOf(rse.getStatusCode().value())
                                : throwable instanceof UpstreamHttpException uhe
                                ? String.valueOf(uhe.statusCode) : "500";
                        recordBilling(protocol, instance, model, System.currentTimeMillis() - startTime,
                                capturedUsage.get() != null, code, throwable.getMessage(),
                                capturedUsage.get(), identity,
                                parseHttpStatus(code),
                                throwable instanceof UpstreamHttpException uhe ? uhe.protocolBody : null);
                    }
                })
                .onErrorResume(throwable -> {
                    // 吞掉错误，避免 fire-and-forget 订阅无 onError 处理抛异常；
                    // 错误已通过 clientSink 通知客户端
                    if (throwable instanceof TimeoutException) {
                        logger.error("透传流式请求超时: protocol={}, instance={}, timeout={}s",
                                protocol, instance.getName(), upstreamTimeout.getSeconds());
                    }
                    return Flux.empty();
                })
                .subscribe();

        // 客户端响应 = sink；客户端断连只影响 sink 订阅，不影响上游计费
        Flux<ServerSentEvent<String>> clientFlux = clientSink.asFlux()
                .doOnCancel(() -> logger.info(
                        "客户端断开透传流式连接，上游将继续消费以完成计费: protocol={}, instance={}",
                        protocol, instance.getName()))
                .onErrorResume(throwable -> {
                    // 上游错误通过 sink 传到客户端，按协议转成 SSE error 事件
                    return Flux.just(buildStreamErrorEvent(protocol, throwable));
                });

        return Mono.just(ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(clientFlux));
    }

    /**
     * 流式中途错误（含上游建连失败、超时）按协议构造 SSE error 事件。
     * Anthropic：{@code event: error} + {"type":"error",...}；OpenAI：data 形式 {"error":{...}}。
     */
    private ServerSentEvent<String> buildStreamErrorEvent(final String protocol, final Throwable throwable) {
        String data;
        if (throwable instanceof UpstreamHttpException uhe && uhe.protocolBody != null) {
            // 上游建连阶段的错误体已按目标协议格式化
            data = uhe.protocolBody;
        } else {
            int status = ProtocolErrorHandler.statusOf(throwable);
            String message = ProtocolErrorHandler.messageOf(throwable);
            data = (PROTOCOL_ANTHROPIC.equals(protocol)
                    ? ProtocolErrorHandler.anthropicError(objectMapper, status, message)
                    : ProtocolErrorHandler.openAiError(objectMapper, status, message)).toString();
        }
        return PROTOCOL_ANTHROPIC.equals(protocol)
                ? ServerSentEvent.<String>builder().event("error").data(data).build()
                : ServerSentEvent.<String>builder().data(data).build();
    }

    // ==================== 辅助 ====================

    private WebClient buildWebClient(final ModelRouterProperties.ModelInstance instance) {
        String baseUrl = instance.getBaseUrl();
        try {
            var tracingFactory = ApplicationContextProvider.getBean(
                    org.unreal.modelrouter.monitor.tracing.client.TracingWebClientFactory.class);
            return tracingFactory.createTracingWebClient(baseUrl);
        } catch (Exception e) {
            return WebClient.builder().baseUrl(baseUrl).build();
        }
    }

    private WebClient.RequestBodySpec buildRequestSpec(final WebClient client,
                                                       final String path,
                                                       final ModelRouterProperties.ModelInstance instance) {
        WebClient.RequestBodySpec spec = client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);

        Map<String, String> headers = instance.getHeaders();
        if (headers != null) {
            headers.forEach((key, value) -> {
                // Content-Type / Accept 已固定，避免重复
                if (!"content-type".equalsIgnoreCase(key) && !"accept".equalsIgnoreCase(key)) {
                    spec.header(key, value);
                }
            });
        }
        return spec;
    }

    /**
     * OpenAI 协议：对命中注入配置的实例（如火山方舟）补充 stream_options.include_usage，
     * 确保流式响应返回 token 用量。若客户端已传 stream_options 则不覆盖。
     */
    private Object injectStreamOptionsIfNeeded(final JsonNode requestNode,
                                               final ModelRouterProperties.ModelInstance instance) {
        try {
            var props = ApplicationContextProvider.getBean(
                    org.unreal.modelrouter.router.adapter.config.StreamUsageInjectionProperties.class);
            if (props == null || !props.isEnabled() || !props.matches(instance.getBaseUrl())) {
                return requestNode;
            }
            if (!requestNode.isObject()) {
                return requestNode;
            }
            com.fasterxml.jackson.databind.node.ObjectNode node = (com.fasterxml.jackson.databind.node.ObjectNode) requestNode;
            if (!node.path("stream").asBoolean(false) || !node.path("stream_options").isMissingNode()) {
                return node;
            }
            com.fasterxml.jackson.databind.node.ObjectNode streamOptions = objectMapper.createObjectNode();
            Map<String, Object> options = props.getOptions();
            if (options != null) {
                options.forEach((k, v) -> {
                    if (v instanceof Boolean b) {
                        streamOptions.put(k, b);
                    } else if (v instanceof Number n) {
                        streamOptions.putPOJO(k, n);
                    } else if (v != null) {
                        streamOptions.put(k, v.toString());
                    }
                });
            }
            if (streamOptions.isEmpty()) {
                streamOptions.put("include_usage", true);
            }
            node.set("stream_options", streamOptions);
            return node;
        } catch (Exception e) {
            logger.warn("stream_options 注入失败, instance={}: {}", instance.getName(), e.getMessage());
            return requestNode;
        }
    }

    /**
     * 从非流式响应体提取 usage（6 维归一化）；失败返回 null。
     * 兼容 OpenAI（prompt_tokens/completion_tokens/total_tokens）与 Anthropic（input_tokens/output_tokens）。
     */
    private org.unreal.modelrouter.billing.usage.TokenUsage extractUsage(final String body,
            final ModelRouterProperties.ModelInstance instance) {
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode usage = node.path("usage");
            if (!usage.isObject()) {
                return null;
            }
            if (!usage.has("prompt_tokens") && !usage.has("total_tokens")
                    && !usage.has("input_tokens") && !usage.has("output_tokens")) {
                return null;
            }
            return ApplicationContextProvider.getBean(
                    org.unreal.modelrouter.billing.usage.TokenUsageExtractor.class)
                    .extract(usage, instance != null ? instance.getVendor() : null,
                            instance != null ? instance.getBaseUrl() : null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 OpenAI 格式 SSE chunk 捕获 usage（上游均为 OpenAI 协议，含 Anthropic 端点转换后的上游）。
     */
    private void captureUsageFromChunk(final String chunk,
                                       final AtomicReference<org.unreal.modelrouter.billing.usage.TokenUsage> capturedUsage,
                                       final AtomicReference<String> lastUsageChunk,
                                       final ModelRouterProperties.ModelInstance instance) {
        try {
            String jsonPart = chunk.trim();
            if (jsonPart.startsWith("data:")) {
                jsonPart = jsonPart.substring(5).trim();
            }
            if (jsonPart.isEmpty() || "[DONE]".equals(jsonPart)) {
                return;
            }
            JsonNode node = objectMapper.readTree(jsonPart);
            JsonNode usage = node.path("usage");
            if (usage.isObject()) {
                capturedUsage.set(ApplicationContextProvider.getBean(
                        org.unreal.modelrouter.billing.usage.TokenUsageExtractor.class)
                        .extract(usage, instance != null ? instance.getVendor() : null,
                                instance != null ? instance.getBaseUrl() : null));
                // 携带 usage 的 chunk 通常为流尾 chunk（含 finish_reason 与 choices 收尾），
                // 留作计费响应快照的 body（响应快照不聚合流式全文）
                lastUsageChunk.set(jsonPart);
            }
        } catch (Exception ignored) {
            // 非 JSON chunk（如注释行），正常忽略
        }
    }

    /**
     * Anthropic 端点：强制注入 stream_options.include_usage=true。
     * 上游是 OpenAI 协议，计费与 Anthropic 流式事件的 usage 字段都依赖该 token 用量。
     */
    private Object forceStreamOptionsIncludeUsage(final JsonNode requestNode) {
        if (!requestNode.isObject()) {
            return requestNode;
        }
        com.fasterxml.jackson.databind.node.ObjectNode node =
                (com.fasterxml.jackson.databind.node.ObjectNode) requestNode;
        com.fasterxml.jackson.databind.node.ObjectNode streamOptions =
                node.path("stream_options").isObject()
                        ? (com.fasterxml.jackson.databind.node.ObjectNode) node.path("stream_options")
                        : objectMapper.createObjectNode();
        streamOptions.put("include_usage", true);
        node.set("stream_options", streamOptions);
        return node;
    }

    /**
     * 上游在流式连接建立阶段返回的 HTTP 错误（携带已按目标协议格式化的错误体）。
     */
    private static final class UpstreamHttpException extends RuntimeException {
        private final int statusCode;
        private final String protocolBody;

        UpstreamHttpException(final int statusCode, final String protocolBody) {
            super("upstream error: " + statusCode);
            this.statusCode = statusCode;
            this.protocolBody = protocolBody;
        }
    }

    private void recordBilling(final String protocol,
                               final ModelRouterProperties.ModelInstance instance,
                               final String model,
                               final long durationMs,
                               final boolean success,
                               final String errorCode,
                               final String errorMessage,
                               final org.unreal.modelrouter.billing.usage.TokenUsage usage,
                               final UserIdentity identity,
                               final Integer httpStatus,
                               final String upstreamBody) {
        long promptTokens = usage != null ? usage.legacyPromptTokens() : 0;
        long completionTokens = usage != null ? usage.legacyCompletionTokens() : 0;
        long totalTokens = usage != null
                ? (usage.rawTotalTokens() > 0 ? usage.rawTotalTokens() : promptTokens + completionTokens)
                : 0;

        // 免费额度（超支接受策略，与既有链路一致）
        FreeQuotaResult freeQuotaResult = null;
        try {
            FreeQuotaService freeQuotaService = ApplicationContextProvider.getBean(FreeQuotaService.class);
            if (identity != null && identity.platformUser()
                    && freeQuotaService.isEnabledFor(identity.userId(), ModelServiceRegistry.ServiceType.chat.name())
                    && freeQuotaService.getRemainingQuota(identity.userId(), ModelServiceRegistry.ServiceType.chat.name()) > 0) {
                freeQuotaResult = freeQuotaService.deductStreamingQuota(
                        identity.userId(), ModelServiceRegistry.ServiceType.chat.name(),
                        usage != null ? usage.billableTotal() : totalTokens);
            }
        } catch (Exception e) {
            logger.warn("透传免费额度扣减失败, model={}: {}", model, e.getMessage());
        }

        try {
            BillingService billingService = ApplicationContextProvider.getBean(BillingService.class);
            JsonNode usageJson = usage != null ? objectMapper.valueToTree(usage) : null;
            String snapshot = responseSnapshotBuilder.build(protocol, success, httpStatus,
                    errorCode, errorMessage, usageJson, upstreamBody);
            var ctx = BillingService.BillingContext.create()
                    .userId(identity != null ? identity.userId() : null)
                    .userAccount(identity != null ? identity.userAccount() : null)
                    .apiKeyId(identity != null ? identity.apiKeyId() : null)
                    .apiKeyName(identity != null ? identity.apiKeyName() : null)
                    .modelName(model)
                    .serviceType(ModelServiceRegistry.ServiceType.chat.name())
                    .provider(protocol)
                    .channelId(instance.getChannelId())
                    .channelName(instance.getName())
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .totalTokens(totalTokens)
                    .tokenUsage(usage)
                    .vendor(instance.getVendor())
                    .baseUrl(instance.getBaseUrl())
                    .isSuccess(success)
                    .errorCode(errorCode)
                    .errorMessage(errorMessage)
                    .responseSnapshot(snapshot)
                    .responseTimeMs(durationMs)
                    .enterpriseId(identity != null ? identity.enterpriseId() : null)
                    .enterpriseName(identity != null ? identity.enterpriseName() : null)
                    .companyId(identity != null ? identity.companyId() : null)
                    .userType(identity != null ? identity.userType() : null)
                    .systemUserId(identity != null ? identity.systemUserId() : null)
                    .platformUser(identity != null ? identity.platformUser() : null)
                    .accountId(resolveAccountId(identity))
                    .accountType(resolveAccountType(identity))
                    .isFreeQuota(freeQuotaResult != null && freeQuotaResult.hitFreeQuota())
                    .freeQuotaConsumed(freeQuotaResult != null ? freeQuotaResult.deductedTokens() : 0L);
            billingService.recordBilling(ctx);
        } catch (Exception e) {
            logger.error("透传计费记录异常, protocol={}, model={}: {}", protocol, model, e.getMessage(), e);
        }
    }

    private static String resolveAccountId(final UserIdentity identity) {
        if (identity == null) {
            return null;
        }
        if (Integer.valueOf(1).equals(identity.userType())) {
            return identity.companyId();
        }
        if (Integer.valueOf(2).equals(identity.userType())) {
            return identity.userId();
        }
        return null;
    }

    private static Integer resolveAccountType(final UserIdentity identity) {
        return identity != null ? identity.userType() : null;
    }

    private static Integer parseHttpStatus(final String code) {
        if (code == null) {
            return null;
        }
        try {
            return Integer.valueOf(code);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Duration getTimeout(final ModelRouterProperties.ModelInstance instance) {
        if (instance != null && instance.getTimeout() != null && instance.getTimeout() > 0) {
            return Duration.ofSeconds(instance.getTimeout());
        }
        return DEFAULT_TIMEOUT;
    }

    private String truncate(final String body) {
        if (body == null) {
            return null;
        }
        return body.length() > 500 ? body.substring(0, 500) : body;
    }

    /**
     * 判断异常是否由客户端主动取消/断开导致（浏览器关闭、SSE 重连等）。
     * 方向B 改造后客户端断连不再传播到上游链，此方法在本类失去调用点，保留备用。
     */
    @SuppressWarnings("unused")
    private boolean isClientCancellationError(final Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        if (throwable instanceof java.io.IOException
                || throwable instanceof java.nio.channels.ClosedChannelException
                || throwable instanceof reactor.netty.channel.AbortedException) {
            String message = throwable.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                return lower.contains("broken pipe")
                        || lower.contains("connection reset")
                        || lower.contains("closed")
                        || lower.contains("abort")
                        || lower.contains("cancel");
            }
        }
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            return isClientCancellationError(cause);
        }
        return false;
    }
}
