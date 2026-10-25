package com.jyshnkr.urlshortener.links.analytics;

import com.jyshnkr.urlshortener.links.model.RedirectEvent;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka transport: publishes one event per redirect, keyed by short code. The calling request waits
 * only while the producer lacks topic metadata or buffer space, bounded by the producer's {@code
 * max.block.ms}; broker acknowledgement is reported asynchronously.
 */
public final class KafkaRedirectPublisher implements RedirectRecording {
  /** Startup waits this long for topic metadata, so the first redirects need not wait for it. */
  public static final Duration STARTUP_METADATA_WAIT = Duration.ofSeconds(5);

  private static final Logger LOG = LoggerFactory.getLogger(KafkaRedirectPublisher.class);
  private static final long WARNING_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();
  private static final long RETRY_PAUSE_NANOS = Duration.ofMillis(100).toNanos();

  /** Submitted events are published (broker-acknowledged), failed or still pending. */
  public record Diagnostics(long submitted, long published, long failed) {
    public long pending() {
      return submitted - published - failed;
    }
  }

  private final KafkaTemplate<String, String> template;
  private final String topic;
  private final Clock clock;
  private final AtomicLong submitted = new AtomicLong();
  private final AtomicLong published = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();
  private final AtomicLong lastWarningNanos =
      new AtomicLong(System.nanoTime() - WARNING_INTERVAL_NANOS);

  public KafkaRedirectPublisher(
      KafkaTemplate<String, String> template, String topic, MeterRegistry metrics, Clock clock) {
    this.template = Objects.requireNonNull(template);
    this.topic = Objects.requireNonNull(topic);
    this.clock = Objects.requireNonNull(clock);
    Objects.requireNonNull(metrics);
    register(metrics, "submitted", submitted);
    register(metrics, "published", published);
    register(metrics, "publish_failed", failed);
    Gauge.builder("shortener.analytics.in.flight", this, publisher -> publisher.pendingCount())
        .register(metrics);
  }

  @Override
  public void record(String code) {
    var event = new RedirectEvent(UUID.randomUUID(), code, clock.instant());
    submitted.incrementAndGet();
    try {
      template
          .send(topic, code, RedirectEventFormat.write(event))
          .whenComplete(
              (result, failure) -> {
                if (failure == null) {
                  published.incrementAndGet();
                } else {
                  failed();
                }
              });
    } catch (RuntimeException failure) {
      // No metadata or buffer space within max.block.ms, or the producer is closing.
      failed();
    }
  }

  /**
   * Fetches topic metadata before traffic arrives. Returns false, after logging a warning, when
   * Kafka is unreachable within {@code limit}; redirects then still succeed but their events fail.
   */
  public boolean awaitTopicMetadata(Duration limit) {
    long deadline = System.nanoTime() + limit.toNanos();
    while (true) {
      try {
        int partitions = template.partitionsFor(topic).size();
        LOG.info("Kafka analytics topic is available with {} partitions", partitions);
        return true;
      } catch (RuntimeException unavailable) {
        if (System.nanoTime() - deadline >= 0 || Thread.currentThread().isInterrupted()) {
          LOG.warn(
              "Kafka analytics topic unavailable after {} ms; redirects continue, but their"
                  + " events fail until Kafka is reachable",
              limit.toMillis());
          return false;
        }
        // Each attempt already waits up to max.block.ms; pause in case a failure is immediate.
        LockSupport.parkNanos(RETRY_PAUSE_NANOS);
      }
    }
  }

  public Diagnostics diagnostics() {
    return new Diagnostics(submitted.get(), published.get(), failed.get());
  }

  private double pendingCount() {
    return diagnostics().pending();
  }

  private void failed() {
    long failures = failed.incrementAndGet();
    long now = System.nanoTime();
    long last = lastWarningNanos.get();
    if (now - last >= WARNING_INTERVAL_NANOS && lastWarningNanos.compareAndSet(last, now)) {
      LOG.warn(
          "Analytics recording incomplete: Kafka publish failures={}, published={}",
          failures,
          published.get());
    }
  }

  private static void register(MeterRegistry metrics, String outcome, AtomicLong value) {
    FunctionCounter.builder("shortener.analytics.events", value, AtomicLong::doubleValue)
        .tag("outcome", outcome)
        .register(metrics);
  }
}
