package com.jyshnkr.urlshortener.links.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jyshnkr.urlshortener.links.analytics.RedirectEventConsumer.Diagnostics;
import com.jyshnkr.urlshortener.links.model.RedirectEvent;
import com.jyshnkr.urlshortener.links.model.RedirectEventBatchResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class RedirectEventConsumerTest {
  private static final class CloseableMeters extends SimpleMeterRegistry implements AutoCloseable {}

  /**
   * Behaves like the database: an event ID is counted once, an event for a short code without a
   * saved link is skipped, and a failed call saves nothing.
   */
  private static final class RecordingWriter implements RedirectEventWriter {
    private final List<List<RedirectEvent>> calls = new CopyOnWriteArrayList<>();
    private final Set<UUID> saved = new HashSet<>();
    private final Set<String> unlinkedCodes = new HashSet<>();
    private volatile RuntimeException failure;
    private volatile boolean closed;

    @Override
    public RedirectEventBatchResult recordNewEvents(List<RedirectEvent> events) {
      if (failure != null) {
        throw failure;
      }
      calls.add(List.copyOf(events));
      int counted = 0;
      int unknown = 0;
      for (var event : events) {
        if (unlinkedCodes.contains(event.code())) {
          unknown++;
        } else if (saved.add(event.eventId())) {
          counted++;
        }
      }
      return new RedirectEventBatchResult(counted, unknown);
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static final Instant AT = Instant.parse("2026-10-25T12:00:00Z");

  @Test
  void newEventsAreSavedWhileDuplicatesAndMalformedMessagesAreOnlyCounted() {
    var writer = new RecordingWriter();
    var first = new RedirectEvent(UUID.randomUUID(), "Abcdef1234", AT);
    var second = new RedirectEvent(UUID.randomUUID(), "Other12345", AT.plusSeconds(1));
    try (var metrics = new CloseableMeters()) {
      var consumer = new RedirectEventConsumer(writer, metrics);
      consumer.consume(
          List.of(
              event(0, first),
              event(1, second),
              event(2, first),
              message(3, "not json"),
              message(4, null),
              message(
                  5,
                  "{\"eventId\":\""
                      + UUID.randomUUID()
                      + "\",\"code\":\"bad\",\"redirectedAt\":\"2026-10-25T12:00:00Z\"}")));

      assertThat(writer.calls).containsExactly(List.of(first, second, first));
      assertThat(consumer.diagnostics()).isEqualTo(new Diagnostics(6, 2, 1, 3, 0));

      // Redelivery after a lost offset commit: already counted, so nothing changes.
      consumer.consume(List.of(event(1, second)));

      assertThat(consumer.diagnostics()).isEqualTo(new Diagnostics(7, 2, 2, 3, 0));
      assertThat(counter(metrics, "recorded")).isEqualTo(2);
      assertThat(counter(metrics, "duplicate")).isEqualTo(2);
      assertThat(counter(metrics, "rejected")).isEqualTo(3);
    }
  }

  @Test
  void eventsForShortCodesWithoutASavedLinkAreRejectedNotCountedAsDuplicates() {
    var writer = new RecordingWriter();
    writer.unlinkedCodes.addAll(Set.of("Absent1234", "Absent5678"));
    try (var metrics = new CloseableMeters()) {
      var consumer = new RedirectEventConsumer(writer, metrics);

      var repeated = new RedirectEvent(UUID.randomUUID(), "Absent5678", AT);
      consumer.consume(
          List.of(
              event(0, new RedirectEvent(UUID.randomUUID(), "Abcdef1234", AT)),
              event(1, new RedirectEvent(UUID.randomUUID(), "Absent1234", AT)),
              event(2, repeated),
              event(3, repeated)));

      // Every copy of an unlinked event is rejected; none is reported as a duplicate.
      assertThat(consumer.diagnostics()).isEqualTo(new Diagnostics(4, 1, 0, 3, 0));
      assertThat(counter(metrics, "recorded")).isEqualTo(1);
      assertThat(counter(metrics, "duplicate")).isZero();
      assertThat(counter(metrics, "rejected")).isEqualTo(3);
    }
  }

  @Test
  void aFailedWriteIsThrownSoTheBatchIsRetriedAndCountsOnlyTheSuccessfulAttempt() {
    var writer = new RecordingWriter();
    var failure = new IllegalStateException("Database unavailable");
    writer.failure = failure;
    var batch = List.of(event(0, new RedirectEvent(UUID.randomUUID(), "Retried001", AT)));
    try (var metrics = new CloseableMeters()) {
      var consumer = new RedirectEventConsumer(writer, metrics);

      assertThatThrownBy(() -> consumer.consume(batch)).isSameAs(failure);
      assertThat(consumer.diagnostics()).isEqualTo(new Diagnostics(0, 0, 0, 0, 1));
      assertThat(metrics.get("shortener.analytics.write.failures").functionCounter().count())
          .isEqualTo(1);

      writer.failure = null;
      consumer.consume(batch);

      assertThat(consumer.diagnostics()).isEqualTo(new Diagnostics(1, 1, 0, 0, 1));
    }
  }

  @Test
  void anEmptyOrEntirelyMalformedBatchIsAcknowledgedWithoutSavingEvents() {
    var writer = new RecordingWriter();
    try (var metrics = new CloseableMeters()) {
      var consumer = new RedirectEventConsumer(writer, metrics);
      consumer.consume(List.of());
      consumer.consume(List.of(message(0, "{}")));
      assertThat(consumer.diagnostics()).isEqualTo(new Diagnostics(1, 0, 0, 1, 0));
      assertThat(writer.calls).allMatch(List::isEmpty);
    }
  }

  @Test
  void closingTheConsumerClosesItsWriter() {
    var writer = new RecordingWriter();
    try (var metrics = new CloseableMeters()) {
      new RedirectEventConsumer(writer, metrics).close();
    }
    assertThat(writer.closed).isTrue();
  }

  private static ConsumerRecord<String, String> event(long offset, RedirectEvent event) {
    return new ConsumerRecord<>(
        "link-redirects", 0, offset, event.code(), RedirectEventFormat.write(event));
  }

  private static ConsumerRecord<String, String> message(long offset, String value) {
    return new ConsumerRecord<>("link-redirects", 0, offset, "Abcdef1234", value);
  }

  private static double counter(SimpleMeterRegistry metrics, String outcome) {
    return metrics
        .get("shortener.analytics.events")
        .tag("outcome", outcome)
        .functionCounter()
        .count();
  }
}
