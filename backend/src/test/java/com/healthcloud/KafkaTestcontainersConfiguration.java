package com.healthcloud;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * A real single-node Kafka broker (KRaft) for the outbox-relay test, wired via {@code @ServiceConnection} so
 * Spring points {@code spring.kafka.bootstrap-servers} at it automatically. Imported ONLY by the relay test — the
 * rest of the suite stays Postgres-only (no broker), so it is unaffected and fast.
 *
 * <p>Uses the Confluent {@code cp-kafka} image via {@link ConfluentKafkaContainer}: Testcontainers 2.0.x's
 * {@code apache/kafka} orchestration mis-computes {@code advertised.listeners} on this host, so the well-supported
 * Confluent container is the reliable choice for a KRaft broker.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainersConfiguration {

    @Bean
    @ServiceConnection
    ConfluentKafkaContainer kafkaContainer() {
        return new ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0");
    }
}
