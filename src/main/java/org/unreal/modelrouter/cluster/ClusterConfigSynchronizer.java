package org.unreal.modelrouter.cluster;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.unreal.modelrouter.persistence.jpa.entity.ConfigEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ConfigRepository;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.ratelimit.RateLimitManager;

/**
 * Reloads shared configuration when another gateway replica commits a newer revision.
 *
 * <p>This task is scheduled programmatically so it keeps running on API replicas where
 * business {@code @Scheduled} jobs are intentionally disabled.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "jairouter.cluster.config-sync.enabled", havingValue = "true")
public class ClusterConfigSynchronizer implements DisposableBean {

    private static final String CONFIG_KEY = "model-router-config";

    private final ConfigRepository configRepository;
    private final ModelServiceRegistry registry;
    private final RateLimitManager rateLimitManager;
    private final TaskScheduler taskScheduler;
    private final ClusterProperties properties;
    private final AtomicReference<Revision> currentRevision = new AtomicReference<>();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile ScheduledFuture<?> future;

    public ClusterConfigSynchronizer(final ConfigRepository configRepository,
                                     final ModelServiceRegistry registry,
                                     final RateLimitManager rateLimitManager,
                                     final TaskScheduler taskScheduler,
                                     final ClusterProperties properties) {
        this.configRepository = configRepository;
        this.registry = registry;
        this.rateLimitManager = rateLimitManager;
        this.taskScheduler = taskScheduler;
        this.properties = properties;
    }

    /** Starts revision polling after the application is ready. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        currentRevision.set(readRevision());
        Duration interval = Duration.ofMillis(Math.max(500L,
                properties.getConfigSync().getIntervalMs()));
        future = taskScheduler.scheduleWithFixedDelay(this::refreshIfChanged, interval);
        log.info("Cluster config synchronization enabled: instanceId={}, interval={}",
                properties.getInstanceId(), interval);
    }

    /** Checks the shared revision and atomically refreshes local runtime caches. */
    public void refreshIfChanged() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            Revision observed = readRevision();
            Revision loaded = currentRevision.get();
            if (observed == null || observed.equals(loaded)) {
                return;
            }

            registry.refreshFromMergedConfig();
            rateLimitManager.updateConfiguration();
            currentRevision.set(observed);
            log.info("Loaded shared config revision on instance {}: version={}, updatedAt={}",
                    properties.getInstanceId(), observed.version(), observed.updatedAt());
        } catch (RuntimeException exception) {
            log.error("Failed to synchronize shared configuration on instance {}",
                    properties.getInstanceId(), exception);
        } finally {
            refreshing.set(false);
        }
    }

    /** @return the last configuration revision loaded by this node. */
    public String getLoadedRevision() {
        Revision revision = currentRevision.get();
        return revision == null ? "none" : revision.toString();
    }

    private Revision readRevision() {
        return configRepository.findFirstByConfigKeyAndIsLatestTrue(CONFIG_KEY)
                .map(this::toRevision)
                .orElse(null);
    }

    private Revision toRevision(final ConfigEntity entity) {
        LocalDateTime updatedAt = entity.getUpdatedAt() != null
                ? entity.getUpdatedAt() : entity.getCreatedAt();
        return new Revision(entity.getVersion(), updatedAt);
    }

    @Override
    public void destroy() {
        ScheduledFuture<?> scheduled = future;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }

    private record Revision(Integer version, LocalDateTime updatedAt) { }
}
