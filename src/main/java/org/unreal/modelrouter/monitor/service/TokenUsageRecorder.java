package org.unreal.modelrouter.monitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Token 使用量记录服务
 *
 * <p>已降级为仅输出 DEBUG 日志，不再写入 {@code token_usage} 表，避免表不存在或事务异常污染主流程/日志。</p>
 *
 * @author JAiRouter Team
 * @since 1.9.5
 */
@Slf4j
@Service
@Deprecated
public class TokenUsageRecorder {

    /**
     * 异步记录 Token 使用量
     *
     * <p>当前实现已降级为仅输出 DEBUG 日志，不再写入 {@code token_usage} 表，
     * 避免该表不存在或事务异常时污染主流程/日志。</p>
     */
    public void recordTokenUsageAsync(
            final String serviceType,
            final String modelName,
            final String provider,
            final String instanceName,
            final String instanceUrl,
            final Long promptTokens,
            final Long completionTokens,
            final Long totalTokens,
            final String traceId,
            final Boolean isSuccess,
            final String errorCode,
            final String errorMessage,
            final Long responseTimeMs) {

        log.debug("Token usage async recording skipped (table deprecated): model={}, totalTokens={}, serviceType={}",
                modelName, totalTokens, serviceType);
    }

    /**
     * 执行 Token 使用量记录（无用户认证）
     *
     * <p>当前实现已降级为仅输出 DEBUG 日志，不再写入 {@code token_usage} 表，
     * 避免该表不存在或事务异常时污染主流程/日志。</p>
     */
    public void recordTokenUsageNoAuth(
            final String serviceType,
            final String modelName,
            final String provider,
            final String instanceName,
            final String instanceUrl,
            final Long promptTokens,
            final Long completionTokens,
            final Long totalTokens,
            final String traceId,
            final String clientIp,
            final Boolean isSuccess,
            final String errorCode,
            final String errorMessage,
            final Long responseTimeMs) {

        log.debug("Token usage no-auth recording skipped (table deprecated): model={}, totalTokens={}, serviceType={}",
                modelName, totalTokens, serviceType);
    }
}
