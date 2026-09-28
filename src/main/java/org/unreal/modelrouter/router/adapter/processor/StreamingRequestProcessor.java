package org.unreal.modelrouter.router.adapter.processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import org.unreal.modelrouter.monitor.monitoring.collector.MetricsCollector;
import org.unreal.modelrouter.router.adapter.config.StreamUsageInjectionProperties;
import org.unreal.modelrouter.router.adapter.transformer.ResponseTransformer;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import org.unreal.modelrouter.auth.security.model.UserIdentity;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.unreal.modelrouter.billing.freequota.FreeQuotaResult;
import org.unreal.modelrouter.billing.freequota.FreeQuotaService;

/**
 * 流式请求处理器
 * 负责 SSE (Server-Sent Events) 格式的流式请求处理
 *
 * @since v2.15.0
 */
@Service
public class StreamingRequestProcessor {

    private static final Logger logger = LoggerFactory.getLogger(StreamingRequestProcessor.class);

    private final MetricsCollector metricsCollector;
    private final ResponseTransformer responseTransformer;
    private final ObjectMapper objectMapper;
    private final FreeQuotaService freeQuotaService;
    private final StreamUsageInjectionProperties streamUsageInjectionProperties;
    private final org.unreal.modelrouter.billing.ResponseSnapshotBuilder responseSnapshotBuilder;

    public StreamingRequestProcessor(final MetricsCollector metricsCollector,
                                      final ResponseTransformer responseTransformer,
                                      final ObjectMapper objectMapper,
                                      final FreeQuotaService freeQuotaService,
                                      final StreamUsageInjectionProperties streamUsageInjectionProperties,
                                      final org.unreal.modelrouter.billing.ResponseSnapshotBuilder responseSnapshotBuilder) {
        this.metricsCollector = metricsCollector;
        this.responseTransformer = responseTransformer;
        this.objectMapper = objectMapper;
        this.freeQuotaService = freeQuotaService;
        this.streamUsageInjectionProperties = streamUsageInjectionProperties;
        this.responseSnapshotBuilder = responseSnapshotBuilder;
    }

    /**
     * 根据配置向流式 chat 请求体注入 stream_options
     */
    private Object injectStreamOptionsIfNeeded(final Object request,
            final ModelServiceRegistry.ServiceType serviceType,
            final ModelRouterProperties.ModelInstance instance) {
        if (streamUsageInjectionProperties == null || !streamUsageInjectionProperties.isEnabled()) {
            logger.debug("stream_options 注入未启用, instance={}", instance != null ? instance.getName() : "null");
            return request;
        }
        if (serviceType != ModelServiceRegistry.ServiceType.chat) {
            logger.debug("stream_options 注入跳过, 非 chat 服务, serviceType={}", serviceType);
            return request;
        }
        if (instance == null) {
            logger.debug("stream_options 注入跳过, instance 为空");
            return request;
        }
        boolean matched = streamUsageInjectionProperties.matches(instance.getBaseUrl());
        logger.debug("stream_options 注入匹配检查, instance={}, baseUrl={}, matched={}",
                instance.getName(), instance.getBaseUrl(), matched);
        if (!matched) {
            return request;
        }
        if (request == null) {
            return null;
        }

        try {
            JsonNode requestNode;
            if (request instanceof JsonNode node) {
                requestNode = node;
            } else {
                requestNode = objectMapper.valueToTree(request);
            }

            if (!requestNode.isObject()) {
                return request;
            }
            ObjectNode objectNode = (ObjectNode) requestNode;

            // 只对流式请求注入
            JsonNode streamNode = objectNode.path("stream");
            if (!streamNode.isBoolean() || !streamNode.asBoolean()) {
                return request;
            }

            // 避免覆盖客户端已传入的 stream_options（本期客户端不传，保留防御性逻辑）
            if (!objectNode.path("stream_options").isMissingNode()) {
                return request;
            }

            ObjectNode streamOptions = objectMapper.createObjectNode();
            Map<String, Object> options = streamUsageInjectionProperties.getOptions();
            if (options != null) {
                options.forEach((key, value) -> {
                    if (value instanceof Boolean b) {
                        streamOptions.put(key, b);
                    } else if (value instanceof Number n) {
                        streamOptions.putPOJO(key, n);
                    } else if (value != null) {
                        streamOptions.put(key, value.toString());
                    }
                });
            }
            if (streamOptions.isEmpty()) {
                streamOptions.put("include_usage", true);
            }
            objectNode.set("stream_options", streamOptions);

            logger.debug("已为实例 {} 注入 stream_options: {}", instance.getName(), streamOptions);
            return objectNode;
        } catch (Exception e) {
            logger.warn("注入 stream_options 失败, instance={}, error={}",
                    instance.getName(), e.getMessage());
            return request;
        }
    }

    /**
     * 处理流式请求
     *
     * @param request            请求对象
     * @param authorization      授权信息
     * @param client             WebClient实例
     * @param path               请求路径
     * @param selectedInstance   选中的实例
     * @param serviceType        服务类型
     * @param adapterType        适配器类型
     * @param transformChunkFn   数据块转换函数（可选）
     * @return 流式响应 ResponseEntity
     */
    public <T> Mono<? extends org.springframework.http.ResponseEntity<?>> processStreamingRequest(
            final T request,
            final String authorization,
            final WebClient client,
            final String path,
            final ModelRouterProperties.ModelInstance selectedInstance,
            final ModelServiceRegistry.ServiceType serviceType,
            final String adapterType,
            final Function<String, String> transformChunkFn) {

        String instanceName = selectedInstance.getName();
        long requestStartTime = System.currentTimeMillis();

        // 用于从 SSE chunk 中捕获 usage 数据
        java.util.concurrent.atomic.AtomicReference<org.unreal.modelrouter.billing.usage.TokenUsage> capturedUsage = new java.util.concurrent.atomic.AtomicReference<>(null);
        // usage 原始结构（快照 usage 字段，与透传链路口径一致）
        java.util.concurrent.atomic.AtomicReference<JsonNode> capturedRawUsage = new java.util.concurrent.atomic.AtomicReference<>(null);
        // 携带 usage 的尾部 chunk（含 finish_reason 与 choices 收尾），作为计费响应快照的 body（不聚合流式全文）
        java.util.concurrent.atomic.AtomicReference<String> snapshotBody = new java.util.concurrent.atomic.AtomicReference<>(null);
        // 防御性标记：防止 doOnComplete/doOnError 都被触发（或 body 被异常地多次订阅）时重复计费
        java.util.concurrent.atomic.AtomicBoolean billingRecorded = new java.util.concurrent.atomic.AtomicBoolean(false);

        logger.debug("开始流式请求: adapter={}, instance={}, path={}", adapterType, instanceName, path);

        // 实例必须配置下游服务的 Authorization，不允许回退到用户认证
        String effectiveAuth = null;
        Map<String, String> instanceHeaders = selectedInstance.getHeaders();
        if (instanceHeaders != null && instanceHeaders.containsKey("Authorization")) {
            effectiveAuth = instanceHeaders.get("Authorization");
            logger.debug("使用实例级 Authorization: instance={}", instanceName);
        }

        if (effectiveAuth == null || effectiveAuth.isBlank()) {
            logger.warn("实例 {} 未配置下游服务 Authorization，下游可能返回 401", instanceName);
        }

        WebClient.RequestBodySpec requestSpecBuilder = client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
        if (effectiveAuth != null && !effectiveAuth.isBlank()) {
            requestSpecBuilder = requestSpecBuilder.header("Authorization", effectiveAuth);
        }
        final WebClient.RequestBodySpec requestSpec = requestSpecBuilder;

        // 应用实例其他自定义headers（排除 Authorization 和 Content-Type，避免重复）
        if (instanceHeaders != null) {
            instanceHeaders.forEach((key, value) -> {
                String lowerKey = key.toLowerCase();
                if (!"authorization".equals(lowerKey) && !"content-type".equals(lowerKey)) {
                    requestSpec.header(key, value);
                }
            });
        }

        // 根据配置注入 stream_options（用于需要显式设置 include_usage 才返回 usage 的平台）
        final Object requestWithStreamOptions = injectStreamOptionsIfNeeded(request, serviceType, selectedInstance);

        return Mono.deferContextual(outerCtx -> {
            UserIdentity identity = outerCtx.getOrDefault(UserIdentity.CONTEXT_KEY, UserIdentity.SYSTEM);

            // === 计费修复（方向B：解耦上游消费与客户端连接）===
            // 背景：OpenAI 兼容流式的 usage chunk 在流末尾才发送。旧实现把上游 Flux 直接作为响应体返回，
            //   客户端（如 Trae）收到正文后立即断连，broken pipe 以 onError 形式杀掉上游订阅，
            //   导致 usage chunk 永远读不到、计费记录丢失（偶发但高频，表现为 Trae 调用不入账）。
            // 解法：用 Sinks.Many 桥接——上游独立订阅、读完整条流（含末尾 usage）后再计费；
            //   客户端只订阅 sink，其断连不再反传到上游，故 usage 一定能捕获到。
            final Sinks.Many<ServerSentEvent<String>> clientSink =
                    Sinks.many().unicast().onBackpressureBuffer();
            final Duration upstreamTimeout = getTimeoutDuration(selectedInstance);

            // 上游消费链：独立订阅，客户端取消不会中断它（这是修复的核心）
            requestSpec
                .bodyValue(requestWithStreamOptions)
                .retrieve()
                .onStatus(org.springframework.http.HttpStatusCode::is5xxServerError, clientResponse -> {
                    logger.error("流式请求5xx错误: instance={}, status={}", instanceName, clientResponse.statusCode());
                    return Mono.error(new org.springframework.web.server.ResponseStatusException(
                            clientResponse.statusCode(), "下游服务错误"));
                })
                .onStatus(org.springframework.http.HttpStatusCode::is4xxClientError, clientResponse -> {
                    logger.error("流式请求4xx错误: instance={}, status={}", instanceName, clientResponse.statusCode());
                    return Mono.error(new org.springframework.web.server.ResponseStatusException(
                            clientResponse.statusCode(), "请求错误"));
                })
                .bodyToFlux(String.class)
                .timeout(upstreamTimeout)
                .doOnNext(chunk -> {
                    // 从 chunk 提取 usage，并转发给客户端；
                    // 客户端已断连时 tryEmitNext 返回 FAIL_CANCELLED，静默忽略，上游继续读完
                    captureUsageFromChunk(chunk, capturedUsage, capturedRawUsage, snapshotBody, selectedInstance);
                    clientSink.tryEmitNext(transformAndWrapChunk(chunk, transformChunkFn));
                })
                .doOnComplete(() -> {
                    recordStreamingComplete(serviceType, adapterType, instanceName, requestStartTime);
                    clientSink.tryEmitComplete();
                    // 上游读完整条流，usage 已捕获，按实际用量计费
                    if (billingRecorded.compareAndSet(false, true)) {
                        recordStreamingTokenUsage(serviceType, adapterType, instanceName,
                                selectedInstance, requestStartTime, capturedUsage.get(), identity, null,
                                capturedRawUsage.get(), snapshotBody.get());
                    }
                })
                .doOnError(throwable -> {
                    recordStreamingError(serviceType, adapterType, instanceName, requestStartTime, throwable);
                    // 上游真实错误（客户端断连已不再传播到此处）：
                    //   已捕获 usage 则按用量计费(success=true)，否则落失败账单(success=false, tokens=0)
                    clientSink.tryEmitError(throwable);
                    if (billingRecorded.compareAndSet(false, true)) {
                        recordStreamingTokenUsage(serviceType, adapterType, instanceName,
                                selectedInstance, requestStartTime, capturedUsage.get(), identity, throwable,
                                capturedRawUsage.get(), snapshotBody.get());
                    }
                })
                .onErrorResume(throwable -> {
                    // 吞掉错误，避免 fire-and-forget 订阅无 onError 处理抛异常；
                    // 错误已通过 clientSink 通知客户端
                    if (throwable instanceof TimeoutException) {
                        logger.error("流式请求超时: instance={}, timeout={}s", instanceName,
                                selectedInstance != null ? selectedInstance.getTimeout() : "N/A");
                    }
                    return Flux.empty();
                })
                .contextWrite(outerCtx)
                .subscribe();

            // 客户端响应 = sink；客户端断连只影响 sink 订阅，不影响上游计费
            Flux<ServerSentEvent<String>> clientFlux = clientSink.asFlux()
                    .doOnCancel(() -> logger.info(
                            "客户端断开流式连接，上游将继续消费以完成计费: adapter={}, instance={}",
                            adapterType, instanceName))
                    .onErrorResume(throwable -> {
                        if (throwable instanceof TimeoutException) {
                            return Flux.error(new org.springframework.web.server.ResponseStatusException(
                                    org.springframework.http.HttpStatus.GATEWAY_TIMEOUT,
                                    "上游服务响应超时，请稍后重试"));
                        }
                        return Flux.error(throwable);
                    });

            return Mono.just(org.springframework.http.ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(clientFlux));
        });
    }

    /**
     * 转换并包装数据块为 SSE 格式
     */
    private ServerSentEvent<String> transformAndWrapChunk(final String chunk,
                                                            final Function<String, String> transformFn) {
        String transformed;
        if (transformFn != null) {
            transformed = transformFn.apply(chunk);
        } else {
            transformed = responseTransformer.transformStreamChunk(chunk);
        }

        return ServerSentEvent.<String>builder()
                .data(transformed)
                .build();
    }

    /**
     * 记录流式请求完成指标
     */
    private void recordStreamingComplete(final ModelServiceRegistry.ServiceType serviceType,
                                          final String adapterType,
                                          final String instanceName,
                                          final long startTime) {
        if (metricsCollector != null) {
            long responseTime = System.currentTimeMillis() - startTime;
            metricsCollector.recordRequest(serviceType.name(), "STREAM", responseTime, "200");
            metricsCollector.recordBackendCall(adapterType, instanceName, responseTime, true);
            logger.debug("流式请求完成: adapter={}, instance={}, duration={}ms",
                    adapterType, instanceName, responseTime);
        }
    }

    /**
     * 记录流式请求错误指标
     */
    private void recordStreamingError(final ModelServiceRegistry.ServiceType serviceType,
                                        final String adapterType,
                                        final String instanceName,
                                        final long startTime,
                                        final Throwable throwable) {
        if (metricsCollector != null) {
            long responseTime = System.currentTimeMillis() - startTime;
            metricsCollector.recordBackendCall(adapterType, instanceName, responseTime, false);
            logger.error("流式请求错误: adapter={}, instance={}, error={}",
                    adapterType, instanceName, throwable.getMessage());
        }
    }

    /**
     * 获取默认的数据块转换器
     */
    public Function<String, String> getDefaultChunkTransformer(final String adapterType) {
        return chunk -> responseTransformer.transformStreamChunk(chunk);
    }

    /**
     * 处理流式请求（使用默认转换器）
     */
    public <T> Mono<? extends org.springframework.http.ResponseEntity<?>> processStreamingRequest(
            final T request,
            final String authorization,
            final WebClient client,
            final String path,
            final ModelRouterProperties.ModelInstance selectedInstance,
            final ModelServiceRegistry.ServiceType serviceType,
            final String adapterType) {

        return processStreamingRequest(request, authorization, client, path,
                selectedInstance, serviceType, adapterType, null);
    }

    /**
     * 从 SSE chunk 中提取 usage 数据（6 维归一化）；命中时同步记录 usage 原始结构与尾部 chunk，
     * 供计费响应快照使用（响应快照不聚合流式全文，与透传链路口径一致）。
     */
    private void captureUsageFromChunk(final String chunk,
            final java.util.concurrent.atomic.AtomicReference<org.unreal.modelrouter.billing.usage.TokenUsage> capturedUsage,
            final java.util.concurrent.atomic.AtomicReference<JsonNode> capturedRawUsage,
            final java.util.concurrent.atomic.AtomicReference<String> snapshotBody,
            final ModelRouterProperties.ModelInstance instance) {
        try {
            String jsonPart = chunk.trim();
            if (jsonPart.startsWith("data: ")) {
                jsonPart = jsonPart.substring(6).trim();
            }
            if ("[DONE]".equals(jsonPart) || jsonPart.isEmpty()) {
                return;
            }
            var node = objectMapper.readTree(jsonPart);
            var usage = node.path("usage");
            if (usage != null && !usage.isMissingNode() && usage.isObject()) {
                if (usage.has("prompt_tokens") || usage.has("total_tokens")
                        || usage.has("input_tokens") || usage.has("output_tokens")) {
                    var tokenUsage = org.unreal.modelrouter.common.util.ApplicationContextProvider.getBean(
                            org.unreal.modelrouter.billing.usage.TokenUsageExtractor.class)
                            .extract(usage, instance != null ? instance.getVendor() : null,
                                    instance != null ? instance.getBaseUrl() : null);
                    capturedUsage.set(tokenUsage);
                    capturedRawUsage.set(usage);
                    // 携带 usage 的 chunk 通常为流尾 chunk，留作计费响应快照的 body
                    snapshotBody.set(jsonPart);
                    logger.debug("从 SSE chunk 捕获 usage: prompt={}, completion={}, total={}",
                            tokenUsage.legacyPromptTokens(), tokenUsage.legacyCompletionTokens(),
                            tokenUsage.rawTotalTokens());
                } else {
                    logger.debug("SSE chunk 中 usage 对象缺少可识别 token 字段: {}", usage);
                }
            }
        } catch (Exception ignored) {
            // 非 JSON chunk，正常情况（如 SSE 注释行），无需日志
        }
    }

    /**
     * 记录流式请求的 Token 使用量
     *
     * @param throwable    失败时的异常；成功或尚未失败时为 null，用于写入 error_code/error_message
     * @param rawUsage     usage 原始结构（快照 usage 字段，可空）
     * @param snapshotBody 携带 usage 的尾部 chunk（快照 body，可空；超时/未捕获时为 null）
     */
    private void recordStreamingTokenUsage(final ModelServiceRegistry.ServiceType serviceType,
            final String adapterType, final String instanceName,
            final ModelRouterProperties.ModelInstance instance,
            final long startTime,             final org.unreal.modelrouter.billing.usage.TokenUsage usage, final UserIdentity identity,
            final Throwable throwable, final JsonNode rawUsage, final String snapshotBody) {
        long promptTokens = usage != null ? usage.legacyPromptTokens() : 0;
        long completionTokens = usage != null ? usage.legacyCompletionTokens() : 0;
        long totalTokens = usage != null
                ? (usage.rawTotalTokens() > 0 ? usage.rawTotalTokens() : promptTokens + completionTokens)
                : 0;
        long duration = System.currentTimeMillis() - startTime;
        boolean success = usage != null;

        // 提取错误码与错误信息，避免失败账单的 error_code/error_message 全为 NULL
        String errorCode = null;
        String errorMessage = null;
        if (throwable != null) {
            if (throwable instanceof org.springframework.web.server.ResponseStatusException rse) {
                errorCode = String.valueOf(rse.getStatusCode().value());
            } else {
                errorCode = "500";
            }
            errorMessage = throwable.getMessage();
        }

        // 同步扣减免费额度。
        // 流式请求在 doOnComplete 时才拿到最终 token 用量，此时响应已经发完，
        // 因此使用 deductStreamingQuota：超支时扣减剩余全部并锁定，不再抛 402 截断。
        FreeQuotaResult freeQuotaResult = null;
        try {
            if (identity != null && identity.platformUser()
                    && freeQuotaService.isEnabledFor(identity.userId(), serviceType.name())
                    && freeQuotaService.getRemainingQuota(identity.userId(), serviceType.name()) > 0) {
                freeQuotaResult = freeQuotaService.deductStreamingQuota(
                        identity.userId(), serviceType.name(),
                        usage != null ? usage.billableTotal() : totalTokens);
            }
        } catch (Exception e) {
            // 免费额度扣减失败不应中断主流程，记录日志后继续保存计费记录（不带免费额度标记）
            logger.warn("流式请求免费额度扣减失败, user={}, serviceType={}, tokens={}: {}",
                    identity != null ? identity.userId() : "null",
                    serviceType.name(), totalTokens, e.getMessage());
        }

        try {
            var recorder = org.unreal.modelrouter.common.util.ApplicationContextProvider.getBean(
                    org.unreal.modelrouter.monitor.service.TokenUsageRecorder.class);

            recorder.recordTokenUsageNoAuth(
                    serviceType.name(), instanceName, adapterType,
                    instanceName, instance.getBaseUrl(),
                    promptTokens, completionTokens, totalTokens,
                    null, null, success, null, null, duration);
        } catch (Exception e) {
            logger.warn("流式Token使用量记录异常: {}", e.getMessage());
        }

        // 流式计费记录（异步，不影响主流程）
        try {
            var billingService = org.unreal.modelrouter.common.util.ApplicationContextProvider.getBean(
                    org.unreal.modelrouter.billing.BillingService.class);
            // 计费响应快照（V5 口径：流式不聚合全文，以携带 usage 的尾部 chunk 为 body；
            // 超时/未捕获时 body 为空，仅留状态/错误/用量元数据；构建失败降级 null）
            Integer httpStatus = success ? 200 : null;
            if (!success && errorCode != null) {
                try {
                    httpStatus = Integer.parseInt(errorCode);
                } catch (NumberFormatException ignored) {
                    // 非数字错误码（如分类后的文本码）不写 httpStatus，与透传链路 parseHttpStatus 口径一致
                }
            }
            String responseSnapshot = responseSnapshotBuilder != null
                    ? responseSnapshotBuilder.build(adapterType, success, httpStatus,
                            errorCode, errorMessage, rawUsage, snapshotBody)
                    : null;
            var ctx = org.unreal.modelrouter.billing.BillingService.BillingContext.create()
                    .userId(identity.userId())
                    .userAccount(identity.userAccount())
                    .apiKeyId(identity.apiKeyId())
                    .apiKeyName(identity.apiKeyName())
                    .modelName(instance.getName())
                    .serviceType(serviceType.name())
                    .provider(adapterType)
                    .channelId(instance.getChannelId())
                    .channelName(instanceName)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .totalTokens(totalTokens)
                    .tokenUsage(usage)
                    .vendor(instance.getVendor())
                    .baseUrl(instance.getBaseUrl())
                    .isSuccess(success)
                    .errorCode(errorCode)
                    .errorMessage(errorMessage)
                    .responseSnapshot(responseSnapshot)
                    .responseTimeMs(duration)
                    .enterpriseId(identity.enterpriseId())
                    .enterpriseName(identity.enterpriseName())
                    .companyId(identity.companyId())
                    .userType(identity.userType())
                    .systemUserId(identity.systemUserId())
                    .platformUser(identity.platformUser())
                    .accountId(resolveAccountId(identity))
                    .accountType(resolveAccountType(identity))
                    .isFreeQuota(freeQuotaResult != null && freeQuotaResult.hitFreeQuota())
                    .freeQuotaConsumed(freeQuotaResult != null ? freeQuotaResult.deductedTokens() : 0L);
            billingService.recordBilling(ctx);
        } catch (Exception e) {
            logger.error("流式计费记录异常, model={}, user={}, enterpriseId={}: {}",
                    instance.getName(), identity != null ? identity.userId() : "null",
                    identity != null ? identity.enterpriseId() : "null", e.getMessage(), e);
        }
    }

    private static String resolveAccountId(org.unreal.modelrouter.auth.security.model.UserIdentity identity) {
        if (Integer.valueOf(1).equals(identity.userType())) {
            return identity.companyId();
        }
        if (Integer.valueOf(2).equals(identity.userType())) {
            return identity.userId();
        }
        return null;
    }

    private static Integer resolveAccountType(org.unreal.modelrouter.auth.security.model.UserIdentity identity) {
        return identity.userType();
    }

    /**
     * 获取请求超时时间。
     * 优先使用渠道配置的超时时间（秒），未配置则默认 120 秒。
     */
    private Duration getTimeoutDuration(ModelRouterProperties.ModelInstance instance) {
        if (instance != null && instance.getTimeout() != null && instance.getTimeout() > 0) {
            logger.debug("模型[{}] 使用渠道超时: {}s", instance.getName(), instance.getTimeout());
            return Duration.ofSeconds(instance.getTimeout());
        }
        logger.debug("模型[{}] 使用默认超时: 120s", instance != null ? instance.getName() : "unknown");
        return Duration.ofSeconds(120);
    }

    /**
     * 判断异常是否由客户端主动取消/断开连接导致（仅 {@link #processStreamingRequest} 之外的场景保留备用）。
     * 注意：自方向B解耦改造后，流式计费链不再依赖本方法——客户端断连不会传播到上游订阅，
     * 上游始终读完整条流后再计费。保留此方法供未来非流式/直通场景复用。
     */
    @SuppressWarnings("unused")
    private boolean isClientCancellationError(final Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        // 直接类型判断
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
        // 递归检查 cause
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            return isClientCancellationError(cause);
        }
        return false;
    }
}