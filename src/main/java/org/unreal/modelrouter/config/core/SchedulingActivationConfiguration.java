package org.unreal.modelrouter.config.core;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables business scheduled jobs only on the designated worker replica.
 *
 * <p>Single-node deployments keep the historical enabled-by-default behaviour.
 * Cluster API replicas set {@code jairouter.scheduling.enabled=false}; exactly one
 * worker replica sets it to {@code true}.</p>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "jairouter.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingActivationConfiguration {
}
