// 文件说明：测试 AsyncTracingProcessorTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.monitor.tracing.async;

import io.opentelemetry.api.trace.SpanContext;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.monitor.tracing.config.TracingConfiguration;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTracingProcessorTest {

    @Test
    void processedTraceIsRemovedFromPendingQueue() throws InterruptedException {
        TracingConfiguration configuration = new TracingConfiguration();
        configuration.getPerformance().getBatch().setSize(1);
        configuration.getPerformance().getBatch().setTimeout(Duration.ofMillis(20));

        AsyncTracingProcessor processor = new AsyncTracingProcessor(configuration);
        processor.start();

        try {
            boolean accepted = processor.submitTraceData(
                    "trace-1", "span-1", "test-operation",
                    System.currentTimeMillis(), 5L, true, SpanContext.getInvalid()
            ).block(Duration.ofSeconds(2));

            assertTrue(accepted);

            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            AsyncTracingProcessor.ProcessingStats stats = processor.getProcessingStats();
            while (stats.getProcessedCount() < 1 && System.nanoTime() < deadline) {
                Thread.sleep(20);
                stats = processor.getProcessingStats();
            }

            assertEquals(1, stats.getProcessedCount());
            assertEquals(0, stats.getQueueSize());
            assertEquals(0, stats.getDroppedCount());
        } finally {
            processor.stop();
        }
    }
}
