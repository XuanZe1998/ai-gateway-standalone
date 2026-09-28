// 文件说明：测试 ExceptionPersistenceServiceTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.monitor.monitoring.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.monitor.tracing.TracingContext;
import org.unreal.modelrouter.persistence.jpa.entity.ExceptionEventEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ExceptionEventRepository;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("deprecation")
class ExceptionPersistenceServiceTest {

    @Mock
    private ExceptionEventRepository exceptionEventRepository;

    @Mock
    private ErrorCodeResolver errorCodeResolver;

    @Mock
    private TracingContext tracingContext;

    @Test
    void persistExceptionKeepsBoundedColumnsWithinDatabaseLimits() {
        ExceptionPersistenceService service = new ExceptionPersistenceService(
                exceptionEventRepository, new ObjectMapper(), errorCodeResolver);
        when(errorCodeResolver.resolveErrorCode(any())).thenReturn("SYS_500");
        when(errorCodeResolver.resolveErrorCategory(any()))
                .thenReturn(ErrorCodeResolver.ErrorCategory.SYSTEM);
        when(errorCodeResolver.resolveHttpStatus(any())).thenReturn("500");
        when(tracingContext.isActive()).thenReturn(true);
        when(tracingContext.getTraceId()).thenReturn("t".repeat(120));
        when(tracingContext.getSpanId()).thenReturn("s".repeat(120));
        when(exceptionEventRepository.save(any(ExceptionEventEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> additionalInfo = Map.of(
                "clientIp", "i".repeat(80),
                "userAgent", "u".repeat(700),
                "serviceName", "n".repeat(150),
                "methodName", "m".repeat(300),
                "className", "c".repeat(700));

        ExceptionEventEntity saved = service.persistException(
                new IllegalStateException("e".repeat(1200)),
                "o".repeat(300),
                tracingContext,
                additionalInfo,
                "x".repeat(1200),
                "stack trace");

        assertNotNull(saved);
        ArgumentCaptor<ExceptionEventEntity> captor = ArgumentCaptor.forClass(ExceptionEventEntity.class);
        org.mockito.Mockito.verify(exceptionEventRepository).save(captor.capture());
        ExceptionEventEntity entity = captor.getValue();

        assertBounded(entity.getExceptionMessage(), 1000);
        assertBounded(entity.getSanitizedMessage(), 1000);
        assertBounded(entity.getOperation(), 255);
        assertBounded(entity.getTraceId(), 100);
        assertBounded(entity.getSpanId(), 100);
        assertBounded(entity.getClientIp(), 50);
        assertBounded(entity.getUserAgent(), 500);
        assertBounded(entity.getServiceName(), 100);
        assertBounded(entity.getMethodName(), 255);
        assertBounded(entity.getClassName(), 500);
        assertEquals("...", entity.getExceptionMessage().substring(997));
    }

    private void assertBounded(final String value, final int maximumLength) {
        assertNotNull(value);
        assertTrue(value.length() <= maximumLength,
                () -> "Expected length <= " + maximumLength + " but was " + value.length());
    }
}
