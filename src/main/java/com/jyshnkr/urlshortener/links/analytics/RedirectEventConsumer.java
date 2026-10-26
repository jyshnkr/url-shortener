package com.jyshnkr.urlshortener.links.analytics;

import com.jyshnkr.urlshortener.links.model.RedirectEvent;
import com.jyshnkr.urlshortener.links.model.RedirectEventBatchResult;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Reads redirect events in batches and saves them through the writer. Returning normally lets the
 * container commit the batch's offsets; a failed write is thrown, so offsets stay uncommitted and
 * the configured error handler retries the same batch.
 */
public final class RedirectEventConsumer implements AutoCloseable {
  /** The listener container's ID, also used to find it in the listener registry. */
  public static final String LISTENER_ID = "link-redirect-events";

  private static final Logger LOG = LoggerFactory.getLogger(RedirectEventConsumer.class);
  private static final long WARNING_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

  /** Counts from successfully handled batches, plus failed write attempts. */
  public record Diagnostics(
      long received, long recorded, long duplicates, long rejected, long failedWrites) {}

  private final RedirectEventWriter writer;
  private final AtomicLong received = new AtomicLong();
  private final AtomicLong recorded = new AtomicLong();
  private final AtomicLong duplicates = new AtomicLong();
  private final AtomicLong rejected = new AtomicLong();
  private final AtomicLong failedWrites = new AtomicLong();
  private final AtomicLong lastFailureWarning =
      new AtomicLong(System.nanoTime() - WARNING_INTERVAL_NANOS);
  private final AtomicLong lastRejectionWarning =
      new AtomicLong(System.nanoTime() - WARNING_INTERVAL_NANOS);

  public RedirectEventConsumer(RedirectEventWriter writer, MeterRegistry metrics) {
    this.writer = Objects.requireNonNull(writer);
    Objects.requireNonNull(metrics);
    register(metrics, "recorded", recorded);
    register(metrics, "duplicate", duplicates);
    register(metrics, "rejected", rejected);
    FunctionCounter.builder(
            "shortener.analytics.write.failures", failedWrites, AtomicLong::doubleValue)
        .register(metrics);
  }

  @KafkaListener(
      id = LISTENER_ID,
      idIsGroup = false,
      topics = "${shortener.analytics.kafka.topic}",
      batch = "true")
  public void consume(List<ConsumerRecord<String, String>> records) {
    var events = new ArrayList<RedirectEvent>(records.size());
    for (var message : records) {
      RedirectEventFormat.read(message.value()).ifPresent(events::add);
    }
    int malformed = records.size() - events.size();
    RedirectEventBatchResult result;
    boolean saved = false;
    try {
      result = writer.recordNewEvents(events);
      saved = true;
    } finally {
      if (!saved) {
        long failures = failedWrites.incrementAndGet();
        if (dueForWarning(lastFailureWarning)) {
          LOG.warn(
              "Analytics recording delayed: database write failed; the batch will be retried"
                  + " (failedWrites={})",
              failures);
        }
      }
    }
    recorded.addAndGet(result.counted());
    duplicates.addAndGet(events.size() - result.counted() - result.unknownLinks());
    int notSavable = malformed + result.unknownLinks();
    if (notSavable > 0) {
      long skipped = rejected.addAndGet(notSavable);
      if (dueForWarning(lastRejectionWarning)) {
        LOG.warn(
            "Analytics recording incomplete: malformed events or events for unknown short links"
                + " skipped (rejected={})",
            skipped);
      }
    }
    // Last, so a reader that sees the batch as received also sees its other counts.
    received.addAndGet(records.size());
  }

  public Diagnostics diagnostics() {
    return new Diagnostics(
        received.get(), recorded.get(), duplicates.get(), rejected.get(), failedWrites.get());
  }

  @Override
  public void close() {
    writer.close();
  }

  /** At most one warning per interval for each kind of problem. */
  private static boolean dueForWarning(AtomicLong lastWarning) {
    long now = System.nanoTime();
    long last = lastWarning.get();
    return now - last >= WARNING_INTERVAL_NANOS && lastWarning.compareAndSet(last, now);
  }

  private static void register(MeterRegistry metrics, String outcome, AtomicLong value) {
    FunctionCounter.builder("shortener.analytics.events", value, AtomicLong::doubleValue)
        .tag("outcome", outcome)
        .register(metrics);
  }
}
