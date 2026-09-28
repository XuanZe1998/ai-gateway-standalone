package org.unreal.modelrouter.router.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.reactive.function.client.WebClient;
import org.unreal.modelrouter.router.adapter.builder.RequestBuilder;
import org.unreal.modelrouter.router.adapter.checker.CapabilityChecker;
import org.unreal.modelrouter.router.adapter.error.AdapterErrorHandler;
import org.unreal.modelrouter.router.adapter.error.ErrorResponseBuilder;
import org.unreal.modelrouter.router.adapter.handler.MultipartRequestHandler;
import org.unreal.modelrouter.router.adapter.handler.ResponseHandler;
import org.unreal.modelrouter.router.adapter.mapper.ResponseMapper;
import org.unreal.modelrouter.router.adapter.metrics.AdapterMetricsRecorder;
import org.unreal.modelrouter.router.adapter.processor.FallbackRequestProcessor;
import org.unreal.modelrouter.router.adapter.processor.HttpRequestProcessor;
import org.unreal.modelrouter.router.adapter.processor.StreamingRequestProcessor;
import org.unreal.modelrouter.router.adapter.request.NonStreamingRequestProcessor;
import org.unreal.modelrouter.router.adapter.retry.RetryPolicy;
import org.unreal.modelrouter.router.adapter.selector.InstanceSelector;
import org.unreal.modelrouter.router.adapter.transformer.ResponseTransformer;
import org.unreal.modelrouter.router.adapter.tracing.AdapterTracingManager;
import org.unreal.modelrouter.router.adapter.util.ModelUtils;
import org.unreal.modelrouter.common.dto.ChatDTO;
import org.unreal.modelrouter.common.dto.EmbeddingDTO;
import org.unreal.modelrouter.common.dto.ImageEditDTO;
import org.unreal.modelrouter.common.dto.ImageGenerateDTO;
import org.unreal.modelrouter.common.dto.RerankDTO;
import org.unreal.modelrouter.common.dto.SttDTO;
import org.unreal.modelrouter.common.dto.TtsDTO;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.common.util.IpUtils;
import org.unreal.modelrouter.common.util.ApplicationContextProvider;
import org.unreal.modelrouter.router.fallback.FallbackStrategy;
import org.unreal.modelrouter.router.fallback.impl.CacheFallbackStrategy;
import org.unreal.modelrouter.persistence.repository.ModelCallStatsRepository;
import reactor.core.publisher.Mono;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Function;

/**
 * BaseAdapter - v2.26.6 精简版
 * 所有请求处理逻辑已完全委托给专门组件。
 */
public abstract class BaseAdapter implements ServiceCapability {

    private final ModelServiceRegistry registry;
    private final ModelCallStatsRepository statsRepository;
    private final RequestBuilder requestBuilder;
    private final ResponseHandler responseHandler;
    private final InstanceSelector instanceSelector;
    private final ResponseTransformer responseTransformer;
    private final CapabilityChecker capabilityChecker;
    private final AdapterErrorHandler errorHandler;
    private final RetryPolicy retryPolicy;
    private final HttpRequestProcessor httpRequestProcessor;
    private final ResponseMapper responseMapper;
    private final AdapterMetricsRecorder metricsRecorder;
    private final AdapterTracingManager tracingManager;
    private final ErrorResponseBuilder errorResponseBuilder;
    private final NonStreamingRequestProcessor nonStreamingProcessor;
    private final MultipartRequestHandler multipartRequestHandler;
    protected final ObjectMapper objectMapper;
    private final Logger logger = LoggerFactory.getLogger(BaseAdapter.class);
    private final org.unreal.modelrouter.billing.BalanceCheckService balanceCheckService;

    private StreamingRequestProcessor streamingRequestProcessor;
    @Autowired(required = false)
    private FallbackRequestProcessor fallbackRequestProcessor;

    @Autowired
    public BaseAdapter(final ModelServiceRegistry registry, final ObjectMapper objectMapper,
                       final ModelCallStatsRepository statsRepository, final RequestBuilder requestBuilder,
                       final ResponseHandler responseHandler, final InstanceSelector instanceSelector,
                       final ResponseTransformer responseTransformer, final CapabilityChecker capabilityChecker,
                       final AdapterErrorHandler errorHandler, final RetryPolicy retryPolicy,
                       final HttpRequestProcessor httpRequestProcessor, final ResponseMapper responseMapper,
                       final AdapterMetricsRecorder metricsRecorder, final AdapterTracingManager tracingManager,
                       final ErrorResponseBuilder errorResponseBuilder,
                       final NonStreamingRequestProcessor nonStreamingProcessor,
                       final MultipartRequestHandler multipartRequestHandler,
                       final org.unreal.modelrouter.billing.BalanceCheckService balanceCheckService) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.statsRepository = statsRepository;
        this.requestBuilder = requestBuilder;
        this.responseHandler = responseHandler;
        this.instanceSelector = instanceSelector;
        this.responseTransformer = responseTransformer;
        this.capabilityChecker = capabilityChecker;
        this.errorHandler = errorHandler;
        this.retryPolicy = retryPolicy;
        this.httpRequestProcessor = httpRequestProcessor;
        this.responseMapper = responseMapper;
        this.metricsRecorder = metricsRecorder;
        this.tracingManager = tracingManager;
        this.errorResponseBuilder = errorResponseBuilder;
        this.nonStreamingProcessor = nonStreamingProcessor;
        this.multipartRequestHandler = multipartRequestHandler;
        this.balanceCheckService = balanceCheckService;
    }

    // ==================== 核心方法 ====================

    private String classifyError(final Throwable throwable) {
        return errorHandler.classifyError(throwable);
    }

    public ModelServiceRegistry getRegistry() { return registry; }

    protected RetryPolicy getRetryPolicy() { return retryPolicy; }

    protected WebClient getWebClient(final ModelServiceRegistry.ServiceType serviceType,
                                     final String modelName, final ServerHttpRequest httpRequest) {
        String clientIp = IpUtils.getClientIp(httpRequest);
        ModelRouterProperties.ModelInstance selectedInstance = selectInstance(serviceType, modelName, clientIp);
        String baseUrl = selectedInstance.getBaseUrl();
        try {
            var tracingFactory = org.unreal.modelrouter.common.util.ApplicationContextProvider.getBean(
                    org.unreal.modelrouter.monitor.tracing.client.TracingWebClientFactory.class);
            return tracingFactory.createTracingWebClient(baseUrl);
        } catch (Exception e) {
            return getRegistry().getClient(serviceType, modelName, clientIp);
        }
    }

    protected Mono<ResponseEntity<String>> checkCapability(final ModelServiceRegistry.ServiceType serviceType) {
        return capabilityChecker.checkCapability(supportCapability(), serviceType);
    }

    // ==================== 请求处理模板方法 ====================

    @SuppressWarnings("all")
    protected <T> Mono processRequest(final T request, final String authorization,
            final ServerHttpRequest httpRequest, final ModelServiceRegistry.ServiceType serviceType,
            final String modelName, final RequestProcessor<T> processor) {
        ModelRouterProperties.ModelInstance selectedInstance =
                selectInstance(serviceType, modelName, IpUtils.getClientIp(httpRequest));
        WebClient client = getWebClient(serviceType, modelName, httpRequest);
        String path = getModelPath(serviceType, modelName);
        long startTime = System.currentTimeMillis();
        String adapterType = getAdapterType();
        String modelNameFromRequest = ModelUtils.getModelNameFromRequest(request);
        tracingManager.recordCallStart(adapterType, selectedInstance, serviceType, modelNameFromRequest);
        return Mono.deferContextual(ctx -> {
            UserIdentity identity = ctx.getOrDefault(UserIdentity.CONTEXT_KEY, UserIdentity.SYSTEM);
            return balanceCheckService.checkBalance(identity, serviceType.name())
                    .then(processRequestWithRetry(request, authorization, client, path, selectedInstance,
                            serviceType, modelNameFromRequest, processor, startTime, 0));
        });
    }

    @SuppressWarnings("all")
    private <T> Mono processRequestWithRetry(final T request, final String authorization,
            final WebClient client, final String path, final ModelRouterProperties.ModelInstance selectedInstance,
            final ModelServiceRegistry.ServiceType serviceType, final String modelName,
            final RequestProcessor<T> processor, final long startTime, final int retryCount) {
        String adapterType = getAdapterType();
        String instanceName = selectedInstance.getName();
        int maxRetries = retryPolicy.getMaxRetriesByServiceType(serviceType);
        return Mono.deferContextual(ctx -> {
            UserIdentity identity = ctx.getOrDefault(UserIdentity.CONTEXT_KEY, UserIdentity.SYSTEM);
            return processor.process(request, authorization, client, path, selectedInstance, serviceType)
                    .doOnSuccess(response -> {
                        long duration = System.currentTimeMillis() - startTime;
                        boolean success = response != null && response.getStatusCode().is2xxSuccessful();
                        if (metricsRecorder != null) {
                            metricsRecorder.recordCompleteCall(adapterType, instanceName, duration, success,
                                    null, modelName, serviceType, selectedInstance);
                        }
                        tracingManager.recordCallComplete(adapterType, selectedInstance, serviceType,
                                ModelUtils.getModelNameFromRequest(request), duration, success);
                        // 记录 Token 使用量
                        recordTokenUsage(adapterType, instanceName, selectedInstance,
                                serviceType, modelName, duration, true, null, response, identity);
                    })
                    .onErrorResume(throwable -> {
                        long duration = System.currentTimeMillis() - startTime;
                        String errorCode = classifyError(throwable);
                        if (metricsRecorder != null) {
                            metricsRecorder.recordCompleteCall(adapterType, instanceName, duration, false,
                                    errorCode, modelName, serviceType, selectedInstance);
                        }
                        if (retryPolicy.canRetry(retryCount, throwable) && retryPolicy.isRetryable(throwable)) {
                            tracingManager.recordRetry(adapterType, selectedInstance, retryCount + 1, maxRetries, throwable);
                            if (metricsRecorder != null) {
                                metricsRecorder.recordRetry(adapterType, instanceName, retryCount + 1, throwable);
                            }
                            long retryDelay = retryPolicy.calculateRetryDelay(retryCount);
                            // 重试中的失败不记录 Token 用量/计费：重试成功后会由 doOnSuccess 记录一次真实用量，
                            // 若此处也记录会产生重复账单（一条全 0 用量的失败账单 + 一条真实账单）
                            return Mono.delay(Duration.ofMillis(retryDelay))
                                    .then(processRequestWithRetry(request, authorization, client, path,
                                            selectedInstance, serviceType, modelName, processor,
                                            System.currentTimeMillis(), retryCount + 1));
                        }
                        // 仅在最终失败（已耗尽重试次数或错误不可重试）时记录 Token 使用量 + 计费
                        recordTokenUsage(adapterType, instanceName, selectedInstance,
                                serviceType, modelName, duration, false, errorCode, null, identity);
                        return errorResponseBuilder.buildErrorResponse(throwable).flatMap(Mono::error);
                    });
        });
    }

    @SuppressWarnings({"all", "unchecked"})
    protected <T> Mono<? extends ResponseEntity<?>> processRequestWithFallback(final T request,
            final String authorization, final ServerHttpRequest httpRequest,
            final ModelServiceRegistry.ServiceType serviceType, final String modelName,
            final RequestProcessor<T> processor) {
        ModelRouterProperties.ServiceConfig serviceConfig = getRegistry().getServiceConfig(serviceType);
        FallbackStrategy<ResponseEntity<?>> fallbackStrategy =
                getRegistry().getFallbackManager().getFallbackStrategy(serviceType.name(), serviceConfig);
        if (fallbackStrategy == null) {
            return processRequest(request, authorization, httpRequest, serviceType, modelName, processor);
        }
        Mono<ResponseEntity<?>> requestMono = (Mono<ResponseEntity<?>>)(Mono) processRequest(
                request, authorization, httpRequest, serviceType, modelName, processor);
        if (fallbackRequestProcessor != null) {
            return requestMono.onErrorResume(throwable -> {
                // 上游响应错误（超时、4xx/5xx）直接透传给客户端，不触发 fallback
                if (isUpstreamResponseError(throwable)) {
                    return Mono.error(throwable);
                }
                return fallbackRequestProcessor.handleFallbackError(throwable, fallbackStrategy);
            });
        }
        return requestMono.onErrorResume(throwable -> {
            // 上游响应错误直接透传
            if (isUpstreamResponseError(throwable)) {
                return Mono.error(throwable);
            }
            return fallbackStrategy.fallback((Exception) throwable) != null
                ? Mono.just(fallbackStrategy.fallback((Exception) throwable))
                : Mono.error(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "服务降级且无缓存"));
        });
    }

    /**
     * 判断是否为上游服务响应错误（应直接透传给客户端，不触发 fallback）。
     * 包括：上游超时、上游返回 4xx/5xx、余额不足等业务级错误。
     * 只有基础设施故障（找不到实例、全熔断、限流）才走 fallback。
     */
    private boolean isUpstreamResponseError(final Throwable throwable) {
        // ResponseStatusException：上游超时(504)、余额不足(402)等业务错误
        if (throwable instanceof org.springframework.web.server.ResponseStatusException rse) {
            return true;
        }
        // WebClientResponseException：上游返回 4xx/5xx
        if (throwable instanceof org.springframework.web.reactive.function.client.WebClientResponseException) {
            return true;
        }
        // DownstreamServiceException：下游服务异常
        if (throwable instanceof org.unreal.modelrouter.common.exception.DownstreamServiceException) {
            return true;
        }
        return false;
    }

    // ==================== 非流式请求处理 ====================

    protected <T> Mono<? extends ResponseEntity<?>> processNonStreamingRequest(final T request,
            final String authorization, final WebClient client, final String path,
            final ModelRouterProperties.ModelInstance selectedInstance,
            final ModelServiceRegistry.ServiceType serviceType, final Class<?> responseType) {
        String adapterType = getAdapterType();
        String finalPath = adaptModelName(path);
        String finalAuth = getAuthorizationHeader(authorization, adapterType);
        Function<Object, Object> transformRequestFn = req -> transformRequest(req, adapterType);
        Function<Object, Object> transformResponseFn = data -> transformResponse(data, adapterType);
        return nonStreamingProcessor.processRequest(request, finalAuth, client, finalPath,
                selectedInstance, serviceType, responseType, adapterType,
                transformRequestFn, transformResponseFn, multipartRequestHandler);
    }

    // ==================== 流式请求处理 ====================

    protected <T> Mono<? extends ResponseEntity<?>> processStreamingRequest(final T request,
            final String authorization, final WebClient client, final String path,
            final ModelRouterProperties.ModelInstance selectedInstance,
            final ModelServiceRegistry.ServiceType serviceType) {
        if (streamingRequestProcessor == null) {
            try {
                streamingRequestProcessor = ApplicationContextProvider.getBean(StreamingRequestProcessor.class);
            } catch (Exception e) {
                logger.error("StreamingRequestProcessor 获取失败: {}", e.getMessage());
                return Mono.error(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "流式请求处理器未配置"));
            }
        }
        String adapterType = getAdapterType();
        String finalPath = adaptModelName(path);
        String finalAuth = getAuthorizationHeader(authorization, adapterType);
        Object transformedRequest = transformRequest(request, adapterType);
        return streamingRequestProcessor.processStreamingRequest(transformedRequest, finalAuth, client,
                finalPath, selectedInstance, serviceType, adapterType);
    }

    // ==================== 抽象方法 ====================

    public abstract AdapterCapabilities supportCapability();
    protected abstract String getAdapterType();

    // ==================== 业务方法入口 ====================

    @SuppressWarnings("all")
    @Override
    public Mono chat(final ChatDTO.Request request, final String authorization, final ServerHttpRequest httpRequest) {
        Mono<ResponseEntity<String>> capabilityCheck = checkCapability(ModelServiceRegistry.ServiceType.chat);
        if (capabilityCheck != null) return capabilityCheck;
        return processRequestWithFallback(request, authorization, httpRequest,
                ModelServiceRegistry.ServiceType.chat, request.model(),
                (req, auth, client, path, instance, st) -> Boolean.TRUE.equals(req.stream())
                        ? processStreamingRequest(req, auth, client, path, instance, st)
                        : processNonStreamingRequest(req, auth, client, path, instance, st, String.class));
    }

    @SuppressWarnings("all")
    @Override
    public Mono embedding(final EmbeddingDTO.Request request, final String authorization, final ServerHttpRequest httpRequest) {
        Mono<ResponseEntity<String>> capabilityCheck = checkCapability(ModelServiceRegistry.ServiceType.embedding);
        if (capabilityCheck != null) return capabilityCheck;
        return processRequestWithFallback(request, authorization, httpRequest,
                ModelServiceRegistry.ServiceType.embedding, request.model(),
                (req, auth, client, path, instance, st) ->
                        processNonStreamingRequest(req, auth, client, path, instance, st, String.class));
    }

    @SuppressWarnings("all")
    @Override
    public Mono rerank(final RerankDTO.Request request, final String authorization, final ServerHttpRequest httpRequest) {
        Mono<ResponseEntity<String>> capabilityCheck = checkCapability(ModelServiceRegistry.ServiceType.rerank);
        if (capabilityCheck != null) return capabilityCheck;
        return processRequestWithFallback(request, authorization, httpRequest,
                ModelServiceRegistry.ServiceType.rerank, request.model(),
                (req, auth, client, path, instance, st) ->
                        processNonStreamingRequest(req, auth, client, path, instance, st, String.class));
    }

    @SuppressWarnings("all")
    @Override
    public Mono tts(final TtsDTO.Request request, final String authorization, final ServerHttpRequest httpRequest) {
        Mono<ResponseEntity<String>> capabilityCheck = checkCapability(ModelServiceRegistry.ServiceType.tts);
        if (capabilityCheck != null) return capabilityCheck;
        return processRequestWithFallback(request, authorization, httpRequest,
                ModelServiceRegistry.ServiceType.tts, request.model(),
                (req, auth, client, path, instance, st) ->
                        processNonStreamingRequest(req, auth, client, path, instance, st, byte[].class));
    }

    @SuppressWarnings("all")
    @Override
    public Mono stt(final SttDTO.Request request, final String authorization, final ServerHttpRequest httpRequest) {
        Mono<ResponseEntity<String>> capabilityCheck = checkCapability(ModelServiceRegistry.ServiceType.stt);
        if (capabilityCheck != null) return capabilityCheck;
        return processRequestWithFallback(request, authorization, httpRequest,
                ModelServiceRegistry.ServiceType.stt, request.model(),
                (req, auth, client, path, instance, st) ->
                        processNonStreamingRequest(req, auth, client, path, instance, st, String.class));
    }

    @SuppressWarnings("all")
    public Mono imageGenerate(final ImageGenerateDTO.Request request, final String authorization,
                              final ServerHttpRequest httpRequest) {
        Mono<ResponseEntity<String>> capabilityCheck = checkCapability(ModelServiceRegistry.ServiceType.imgGen);
        if (capabilityCheck != null) return capabilityCheck;
        return processRequestWithFallback(request, authorization, httpRequest,
                ModelServiceRegistry.ServiceType.imgGen, request.model(),
                (req, auth, client, path, instance, st) ->
                        processNonStreamingRequest(req, auth, client, path, instance, st, String.class));
    }

    @SuppressWarnings("all")
    public Mono imageEdit(final ImageEditDTO.Request request, final String authorization,
                          final ServerHttpRequest httpRequest) {
        Mono<ResponseEntity<String>> capabilityCheck = checkCapability(ModelServiceRegistry.ServiceType.imgEdit);
        if (capabilityCheck != null) return capabilityCheck;
        return processRequestWithFallback(request, authorization, httpRequest,
                ModelServiceRegistry.ServiceType.imgEdit, request.model(),
                (req, auth, client, path, instance, st) ->
                        processNonStreamingRequest(req, auth, client, path, instance, st, String.class));
    }

    // ==================== 子类可重写方法 ====================

    protected <T> WebClient.RequestBodySpec configureRequestHeaders(
            final WebClient.RequestBodySpec requestSpec, final T request) {
        return multipartRequestHandler.configureRequestHeaders(requestSpec, request);
    }

    protected <T> WebClient.RequestBodySpec configureRequestHeaders(
            final WebClient.RequestBodySpec requestSpec, final T request,
            final ModelRouterProperties.ModelInstance instance) {
        return multipartRequestHandler.configureRequestHeaders(requestSpec, request, instance);
    }

    protected String transformStreamChunk(final String chunk) {
        return responseTransformer.transformStreamChunk(chunk);
    }

    protected long calculateRequestSize(final Object request) {
        if (request == null) return 0;
        try { return request.toString().getBytes().length; } catch (Exception e) { return 0; }
    }

    // ==================== 辅助方法（委托） ====================

    protected ModelRouterProperties.ModelInstance selectInstance(
            final ModelServiceRegistry.ServiceType serviceType, final String modelName, final String clientIp) {
        return instanceSelector.selectInstance(serviceType, modelName, clientIp);
    }

    protected String getModelPath(final ModelServiceRegistry.ServiceType serviceType, final String modelName) {
        return instanceSelector.getModelPath(serviceType, modelName);
    }

    protected Object transformRequest(final Object request, final String adapterType) {
        return responseTransformer.transformRequest(request, adapterType);
    }

    protected String adaptModelName(final String originalModelName) {
        return responseTransformer.adaptModelName(originalModelName);
    }

    protected Object transformResponse(final Object responseData, final String adapterType) {
        return responseTransformer.transformResponse(responseData, adapterType);
    }

    protected String getAuthorizationHeader(final String authorization, final String adapterType) {
        return authorization;
    }

    /**
     * 记录 Token 使用量到数据库
     */
    private void recordTokenUsage(final String adapterType, final String instanceName,
            final ModelRouterProperties.ModelInstance instance,
            final ModelServiceRegistry.ServiceType serviceType, final String modelName,
            final long durationMs, final boolean success, final String errorCode,
            final ResponseEntity<?> response, final UserIdentity identity) {
        // 流式请求的 Token 用量 + 计费由 StreamingRequestProcessor.doOnComplete/doOnError 统一处理；
        // 此处 response.getBody() 为尚未订阅的 Flux，无法提取 usage，直接跳过避免产生用量为 0 的重复记录
        if (response != null && response.getBody() instanceof reactor.core.publisher.Flux) {
            return;
        }

        long promptTokens = 0, completionTokens = 0, totalTokens = 0;
        org.unreal.modelrouter.billing.usage.TokenUsage tokenUsage = null;
        // 计费响应快照素材：非流式成功场景的上游响应体原文（chat/imgGen 等为 JSON；
        // TTS 等二进制音频体不入库，仅留 usage/状态元数据）
        String upstreamBody = success && response != null && response.getBody() instanceof String s ? s : null;

        try {
            var recorder = ApplicationContextProvider.getBean(
                    org.unreal.modelrouter.monitor.service.TokenUsageRecorder.class);

            // 从非流式响应中提取 usage（6 维归一化）
            if (success && response != null && response.getBody() != null) {
                var node = objectMapper.valueToTree(response.getBody());
                var dataNode = node.path("data");
                var usage = dataNode.has("usage") ? dataNode.path("usage") : node.path("usage");
                if (usage != null && !usage.isMissingNode() && usage.isObject()) {
                    tokenUsage = ApplicationContextProvider.getBean(
                            org.unreal.modelrouter.billing.usage.TokenUsageExtractor.class)
                            .extract(usage, instance.getVendor(), instance.getBaseUrl());
                    promptTokens = tokenUsage.legacyPromptTokens();
                    completionTokens = tokenUsage.legacyCompletionTokens();
                    totalTokens = tokenUsage.rawTotalTokens() > 0
                            ? tokenUsage.rawTotalTokens()
                            : promptTokens + completionTokens;
                    // 图片生成等接口使用 input_tokens / output_tokens 字段名（非对话场景无缓存维度，走旧逻辑）
                    if (promptTokens == 0) {
                        promptTokens = usage.path("input_tokens").asLong(0);
                    }
                    if (completionTokens == 0) {
                        completionTokens = usage.path("output_tokens").asLong(0);
                    }
                    if (totalTokens == 0) {
                        totalTokens = promptTokens + completionTokens;
                    }
                    // 兜底分支提取到 token 但归一化为空时，补建 TokenUsage 保证 6 维落表不缺失
                    if (tokenUsage.billableTotal() == 0 && (promptTokens > 0 || completionTokens > 0)) {
                        tokenUsage = new org.unreal.modelrouter.billing.usage.TokenUsage(
                                promptTokens, 0, 0, 0, completionTokens, 0,
                                promptTokens, completionTokens, totalTokens);
                    }
                }
            }

            recorder.recordTokenUsageNoAuth(
                    serviceType.name(), modelName, adapterType,
                    instanceName, instance.getBaseUrl(),
                    promptTokens, completionTokens, totalTokens,
                    null, null,
                    success, errorCode, null, durationMs);
        } catch (Exception e) {
            logger.warn("Token使用量记录异常, model={}: {}", modelName, e.getMessage());
        }

        // 计费记录（异步，不影响主流程）
        recordBilling(adapterType, instanceName, instance, serviceType, modelName,
                durationMs, success, errorCode, promptTokens, completionTokens, totalTokens, tokenUsage,
                upstreamBody, identity);
    }

    private void recordBilling(final String adapterType, final String instanceName,
            final ModelRouterProperties.ModelInstance instance,
            final ModelServiceRegistry.ServiceType serviceType, final String modelName,
            final long durationMs, final boolean success, final String errorCode,
            final long promptTokens, final long completionTokens, final long totalTokens,
            final org.unreal.modelrouter.billing.usage.TokenUsage tokenUsage,
            final String upstreamBody, final UserIdentity identity) {
        org.unreal.modelrouter.billing.freequota.FreeQuotaResult freeQuotaResult = null;

        // 免费额度检查：独立 try-catch，避免额度服务故障影响计费记录保存
        // 流式和非流式统一采用“超支接受”策略：实际用量超过剩余额度时，扣光剩余额度并锁定 trial_exhausted，
        // 不再抛 402 截断响应（流式已发完的内容无法撤回，非流式也不再白调上游后吞响应）。
        try {
            org.unreal.modelrouter.billing.freequota.FreeQuotaService freeQuotaService = ApplicationContextProvider.getBean(
                    org.unreal.modelrouter.billing.freequota.FreeQuotaService.class);
            if (identity != null && identity.platformUser()
                    && freeQuotaService.isEnabledFor(identity.userId(), serviceType.name())
                    && freeQuotaService.getRemainingQuota(identity.userId(), serviceType.name()) > 0) {
                freeQuotaResult = freeQuotaService.deductStreamingQuota(
                        identity.userId(), serviceType.name(),
                        tokenUsage != null ? tokenUsage.billableTotal() : totalTokens);
            }
        } catch (Exception e) {
            // FreeQuotaService 不可用或其他异常：记录日志，继续保存计费记录（不带免费额度标记）
            logger.warn("免费额度服务不可用，继续保存计费记录, model={}, user={}: {}",
                    modelName, identity != null ? identity.userId() : "null", e.getMessage());
        }

        // 计费记录（异步，不影响主流程）
        try {
            var billingService = ApplicationContextProvider.getBean(
                    org.unreal.modelrouter.billing.BillingService.class);
            // 计费响应快照（V5 口径：非流式落完整响应体；8KB 截断 + base64 脱敏由 builder 负责，
            // 素材为空/构建失败降级 null，不影响落账主链路）
            String responseSnapshot = null;
            var snapshotBuilder = ApplicationContextProvider.getBean(
                    org.unreal.modelrouter.billing.ResponseSnapshotBuilder.class);
            if (snapshotBuilder != null && upstreamBody != null) {
                com.fasterxml.jackson.databind.JsonNode usageJson =
                        tokenUsage != null ? objectMapper.valueToTree(tokenUsage) : null;
                responseSnapshot = snapshotBuilder.build(adapterType, success,
                        success ? 200 : null, errorCode, null, usageJson, upstreamBody);
            }
            var ctx = org.unreal.modelrouter.billing.BillingService.BillingContext.create()
                    .userId(identity != null ? identity.userId() : null)
                    .userAccount(identity != null ? identity.userAccount() : null)
                    .apiKeyId(identity != null ? identity.apiKeyId() : null)
                    .apiKeyName(identity != null ? identity.apiKeyName() : null)
                    .modelName(modelName)
                    .serviceType(serviceType.name())
                    .provider(adapterType)
                    .channelId(instance.getChannelId())
                    .channelName(instanceName)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .totalTokens(totalTokens)
                    .tokenUsage(tokenUsage)
                    .vendor(instance.getVendor())
                    .baseUrl(instance.getBaseUrl())
                    .isSuccess(success)
                    .errorCode(errorCode)
                    .responseSnapshot(responseSnapshot)
                    .responseTimeMs(durationMs)
                    .enterpriseId(identity != null ? identity.enterpriseId() : null)
                    .enterpriseName(identity != null ? identity.enterpriseName() : null)
                    .companyId(identity != null ? identity.companyId() : null)
                    .userType(identity != null ? identity.userType() : null)
                    .systemUserId(identity != null ? identity.systemUserId() : null)
                    .platformUser(identity != null ? identity.platformUser() : null)
                    .accountId(identity != null ? resolveAccountId(identity) : null)
                    .accountType(identity != null ? resolveAccountType(identity) : null)
                    .isFreeQuota(freeQuotaResult != null && freeQuotaResult.hitFreeQuota())
                    .freeQuotaConsumed(freeQuotaResult != null ? freeQuotaResult.deductedTokens() : 0L);
            billingService.recordBilling(ctx);
        } catch (Exception e) {
            logger.error("计费记录异常, model={}, user={}, enterpriseId={}: {}",
                    modelName, identity != null ? identity.userId() : "null",
                    identity != null ? identity.enterpriseId() : "null", e.getMessage(), e);
        }
    }

    private static String resolveAccountId(UserIdentity identity) {
        if (Integer.valueOf(1).equals(identity.userType())) {
            return identity.companyId();
        }
        if (Integer.valueOf(2).equals(identity.userType())) {
            return identity.userId();
        }
        return null;
    }

    private static Integer resolveAccountType(UserIdentity identity) {
        return identity.userType();
    }

    protected void cacheSuccessfulResponse(final ModelServiceRegistry.ServiceType serviceType,
            final String modelName, final ResponseEntity<?> response, final ServerHttpRequest httpRequest) {
        ModelRouterProperties.ServiceConfig serviceConfig = getRegistry().getServiceConfig(serviceType);
        FallbackStrategy<ResponseEntity<?>> fallbackStrategy =
                getRegistry().getFallbackManager().getFallbackStrategy(serviceType.name(), serviceConfig);
        if (fallbackStrategy instanceof CacheFallbackStrategy) {
            ((CacheFallbackStrategy) fallbackStrategy).cacheResponse(serviceType, modelName, httpRequest, response);
        }
    }

    protected void logAdapterRetryEvent(final String adapterType,
            final ModelRouterProperties.ModelInstance instance, final int retryCount,
            final int maxRetries, final Throwable error) {
        tracingManager.recordRetry(adapterType, instance, retryCount, maxRetries, error);
        if (metricsRecorder != null) {
            metricsRecorder.recordRetry(adapterType, instance != null ? instance.getName() : "unknown", retryCount, error);
        }
    }

    protected void logAdapterTransformError(final String adapterType, final Throwable error) {
        tracingManager.recordTransformError(adapterType, error);
        if (metricsRecorder != null) {
            metricsRecorder.recordError(adapterType, "unknown", "TRANSFORM_ERROR", error, 0, null);
        }
    }

    // ==================== 函数式接口 ====================

    @FunctionalInterface
    protected interface RequestProcessor<T> {
        Mono<? extends ResponseEntity<?>> process(T request, String authorization, WebClient client,
                String path, ModelRouterProperties.ModelInstance selectedInstance,
                ModelServiceRegistry.ServiceType serviceType);
    }
}