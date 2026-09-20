package com.healthcloud.observability;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Observability wiring (Phase 11 slice 3). Registers the {@link ObservedAspect} so a method annotated
 * with {@code @Observed} becomes an observation — a metric timer AND a tracing span (via the Micrometer
 * Tracing OTel bridge). The ObservationRegistry is auto-configured by Spring Boot.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }
}
