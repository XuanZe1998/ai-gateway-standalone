package org.unreal.modelrouter.auth.security.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.auth.security.model.SecurityAuditEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 安全审计服务空实现（始终激活的兜底 Bean）。
 * 确保任何环境下都有 SecurityAuditService 可注入，
 * 当 storage=memory 或 storage=database 配置时，
 * 对应的实现通过 @Primary 覆盖此 Bean。
 */
@Slf4j
@Service
public class NoOpSecurityAuditService implements SecurityAuditService {

    public NoOpSecurityAuditService() {
        log.info("安全审计服务: NoOp（审计日志功能关闭）");
    }

    @Override
    public Mono<Void> recordEvent(SecurityAuditEvent event) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> recordAuthenticationEvent(String userId, String clientIp, String userAgent,
                                                 boolean success, String failureReason) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> recordSanitizationEvent(String userId, String contentType, String ruleId, int matchCount) {
        return Mono.empty();
    }

    @Override
    public Flux<SecurityAuditEvent> queryEvents(LocalDateTime startTime, LocalDateTime endTime,
                                                 String eventType, String userId, int limit) {
        return Flux.empty();
    }

    @Override
    public Mono<Map<String, Object>> getSecurityStatistics(LocalDateTime startTime, LocalDateTime endTime) {
        Map<String, Object> empty = new HashMap<>();
        empty.put("totalEvents", 0L);
        return Mono.just(empty);
    }

    @Override
    public Mono<Long> cleanupExpiredLogs(int retentionDays) {
        return Mono.just(0L);
    }

    @Override
    public Mono<Boolean> shouldTriggerAlert(String eventType, int timeWindowMinutes, int threshold) {
        return Mono.just(false);
    }
}
