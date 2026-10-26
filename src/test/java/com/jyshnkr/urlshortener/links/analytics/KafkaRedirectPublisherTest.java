package com.jyshnkr.urlshortener.links.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jyshnkr.urlshortener.links.analytics.KafkaRedirectPublisher.Diagnostics;
import com.jyshnkr.urlshortener.links.model.RedirectEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaRedirectPublisherTest {
  private static final class CloseableMeters extends SimpleMeterRegistry implements AutoCloseable {}

  private static final String TOPIC = "link-redirects";
  private static final Instant NOW = Instant.parse("2026-10-25T12:00:00.123456Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);

  @Test
  void eachRedirectIsSentOnceKeyedByCodeWithItsOwnEventIdWithoutWaitingForTheBroker() {
    var acknowledgements = new ArrayList<CompletableFuture<SendResult<String, String>>>();
    when(template.send(anyString(), anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              var acknowledgement = new CompletableFuture<SendResult<String, String>>();
              acknowledgements.add(acknowledgement);
              return acknowledgement;
            });
    try (var metrics = new CloseableMeters()) {
      var publisher = new KafkaRedirectPublisher(template, TOPIC, metrics, CLOCK);
      assertTimeoutPreemptively(
          Duration.ofMillis(500),
          () -> {
            publisher.record("Abcdef1234");
            publisher.record("Abcdef1234");
          });
      assertThat(publisher.diagnostics()).isEqualTo(new Diagnostics(2, 0, 0));
      assertThat(metrics.get("shortener.analytics.in.flight").gauge().value()).isEqualTo(2);

      acknowledgements.forEach(acknowledgement -> acknowledgement.complete(null));

      assertThat(publisher.diagnostics()).isEqualTo(new Diagnostics(2, 2, 0));
      assertThat(metrics.get("shortener.analytics.in.flight").gauge().value()).isZero();
      assertThat(
              metrics
                  .get("shortener.analytics.events")
                  .tag("outcome", "published")
                  .functionCounter()
                  .count())
          .isEqualTo(2);
      var messages = ArgumentCaptor.forClass(String.class);
      verify(template, times(2)).send(eq(TOPIC), eq("Abcdef1234"), messages.capture());
      var events =
          messages.getAllValues().stream()
              .map(message -> RedirectEventFormat.read(message).orElseThrow())
              .toList();
      assertThat(events).extracting(RedirectEvent::code).containsOnly("Abcdef1234");
      assertThat(events).extracting(RedirectEvent::redirectedAt).containsOnly(NOW);
      assertThat(events).extracting(RedirectEvent::eventId).doesNotHaveDuplicates();
    }
  }

  @Test
  void immediateAndAcknowledgementFailuresAreCountedWithoutReachingTheRedirect() {
    var acknowledgement = new CompletableFuture<SendResult<String, String>>();
    when(template.send(anyString(), anyString(), anyString()))
        .thenThrow(new KafkaException("No topic metadata within max.block.ms"))
        .thenReturn(acknowledgement);
    try (var metrics = new CloseableMeters()) {
      var publisher = new KafkaRedirectPublisher(template, TOPIC, metrics, CLOCK);
      publisher.record("Failed0001");
      publisher.record("Failed0002");
      assertThat(publisher.diagnostics()).isEqualTo(new Diagnostics(2, 0, 1));

      acknowledgement.completeExceptionally(new KafkaException("Delivery timed out"));

      assertThat(publisher.diagnostics()).isEqualTo(new Diagnostics(2, 0, 2));
      assertThat(publisher.diagnostics().pending()).isZero();
      assertThat(
              metrics
                  .get("shortener.analytics.events")
                  .tag("outcome", "publish_failed")
                  .functionCounter()
                  .count())
          .isEqualTo(2);
    }
  }

  @Test
  void startupWaitsForTopicMetadataButNeverBeyondItsLimit() {
    when(template.partitionsFor(TOPIC))
        .thenThrow(new KafkaException("Not yet available"))
        .thenReturn(List.of(new PartitionInfo(TOPIC, 0, null, null, null)));
    try (var metrics = new CloseableMeters()) {
      var publisher = new KafkaRedirectPublisher(template, TOPIC, metrics, CLOCK);
      assertThat(publisher.awaitTopicMetadata(Duration.ofSeconds(5))).isTrue();
      verify(template, times(2)).partitionsFor(TOPIC);
    }

    var unavailable = mockTemplate();
    when(unavailable.partitionsFor(TOPIC)).thenThrow(new KafkaException("Broker unreachable"));
    try (var metrics = new CloseableMeters()) {
      var publisher = new KafkaRedirectPublisher(unavailable, TOPIC, metrics, CLOCK);
      boolean available =
          assertTimeoutPreemptively(
              Duration.ofSeconds(2), () -> publisher.awaitTopicMetadata(Duration.ofMillis(300)));
      assertThat(available).isFalse();
      verify(unavailable, atLeast(2)).partitionsFor(TOPIC);
    }
  }

  @SuppressWarnings("unchecked")
  private static KafkaTemplate<String, String> mockTemplate() {
    return mock(KafkaTemplate.class);
  }
}
