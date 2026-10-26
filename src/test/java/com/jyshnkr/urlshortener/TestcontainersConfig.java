package com.jyshnkr.urlshortener;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfig {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer("postgres:17.11");
  }

  // One broker per test context, so cached contexts never share a consumer group or topic.
  @Bean
  @ServiceConnection
  KafkaContainer kafkaContainer() {
    return kafka();
  }

  /** A disposable single-node KRaft broker; a small heap keeps several cached contexts light. */
  static KafkaContainer kafka() {
    return new KafkaContainer("apache/kafka:4.2.2").withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m");
  }
}
