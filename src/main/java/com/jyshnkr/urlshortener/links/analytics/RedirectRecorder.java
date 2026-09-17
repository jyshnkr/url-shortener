package com.jyshnkr.urlshortener.links.analytics;

import com.jyshnkr.urlshortener.links.model.RedirectIncrement;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RedirectRecorder implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(RedirectRecorder.class);

  private record Event(String code, Instant at) {}

  public record Diagnostics(
      long submitted,
      long confirmedWrites,
      long queueFullDrops,
      long shutdownDrops,
      long unconfirmedWrites,
      int queued,
      int inFlight,
      boolean workerAlive) {}

  private final AnalyticsBatchWriter writer;
  private final RecordingSettings settings;
  private final Clock clock;
  private final ArrayBlockingQueue<Event> queue;
  private final Object admission = new Object();
  private final AtomicLong submitted = new AtomicLong();
  private final AtomicLong confirmed = new AtomicLong();
  private final AtomicLong fullDrops = new AtomicLong();
  private final AtomicLong shutdownDrops = new AtomicLong();
  private final AtomicLong unconfirmed = new AtomicLong();
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Thread worker;
  private volatile boolean accepting = true;
  private volatile boolean abandon;
  private long lastWarningNanos = System.nanoTime() - Duration.ofSeconds(10).toNanos();
  private long lastWarningLosses;

  public RedirectRecorder(
      AnalyticsBatchWriter writer, MeterRegistry metrics, Clock clock, RecordingSettings settings) {
    this.writer = Objects.requireNonNull(writer);
    this.settings = Objects.requireNonNull(settings);
    this.clock = Objects.requireNonNull(clock);
    Objects.requireNonNull(metrics);
    queue = new ArrayBlockingQueue<>(settings.queueCapacity());
    register(metrics, "submitted", submitted);
    register(metrics, "confirmed", confirmed);
    register(metrics, "queue_full", fullDrops);
    register(metrics, "shutdown", shutdownDrops);
    register(metrics, "unconfirmed", unconfirmed);
    Gauge.builder("shortener.analytics.queue.depth", queue, ArrayBlockingQueue::size)
        .register(metrics);
    Gauge.builder("shortener.analytics.in.flight", inFlight, AtomicInteger::get).register(metrics);
    worker =
        Thread.ofPlatform().name("redirect-analytics-writer").daemon(true).unstarted(this::run);
    worker.start();
  }

  public void record(String code) {
    var event = new Event(code, clock.instant());
    // Admission versus shutdown only: never SQL, logging or waiting for queue space.
    synchronized (admission) {
      submitted.incrementAndGet();
      if (!accepting) {
        shutdownDrops.incrementAndGet();
      } else if (!queue.offer(event)) {
        fullDrops.incrementAndGet();
      }
    }
  }

  public Diagnostics diagnostics() {
    return new Diagnostics(
        submitted.get(),
        confirmed.get(),
        fullDrops.get(),
        shutdownDrops.get(),
        unconfirmed.get(),
        queue.size(),
        inFlight.get(),
        worker.isAlive());
  }

  private static void register(MeterRegistry metrics, String outcome, AtomicLong value) {
    FunctionCounter.builder("shortener.analytics.events", value, AtomicLong::doubleValue)
        .tag("outcome", outcome)
        .register(metrics);
  }

  private void run() {
    try {
      while (!abandon && (accepting || !queue.isEmpty())) {
        var events = new ArrayList<Event>();
        try {
          var first = queue.poll(settings.flushInterval().toNanos(), TimeUnit.NANOSECONDS);
          if (first == null) {
            warnAboutLosses();
            continue;
          }
          events.add(first);
          inFlight.set(1);
          long flushAt = System.nanoTime() + settings.flushInterval().toNanos();
          while (events.size() < settings.batchSize() && !abandon) {
            queue.drainTo(events, settings.batchSize() - events.size());
            inFlight.set(events.size());
            long remaining = flushAt - System.nanoTime();
            if (events.size() == settings.batchSize() || !accepting || remaining <= 0) {
              break;
            }
            var next = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (next != null) {
              events.add(next);
              inFlight.set(events.size());
            }
          }
          if (abandon) {
            shutdownDrops.addAndGet(events.size());
          } else {
            write(events);
          }
        } catch (InterruptedException interrupted) {
          shutdownDrops.addAndGet(events.size());
          Thread.currentThread().interrupt();
          break;
        } finally {
          inFlight.set(0);
        }
        warnAboutLosses();
      }
    } finally {
      discardQueued();
      warnAboutLosses();
    }
  }

  private void write(List<Event> events) {
    var increments = new TreeMap<String, RedirectIncrement>();
    for (var event : events) {
      increments.merge(
          event.code(),
          new RedirectIncrement(event.code(), 1, event.at()),
          (a, b) ->
              new RedirectIncrement(
                  a.code(),
                  a.count() + b.count(),
                  a.lastRedirectedAt().isAfter(b.lastRedirectedAt())
                      ? a.lastRedirectedAt()
                      : b.lastRedirectedAt()));
    }
    try {
      // Sorted keys give simultaneous writers a consistent row-lock order.
      writer.write(List.copyOf(increments.values()));
      confirmed.addAndGet(events.size());
    } catch (RuntimeException failure) {
      // A lost acknowledgement may hide a commit. Retrying could double-count this batch.
      unconfirmed.addAndGet(events.size());
    }
  }

  private void warnAboutLosses() {
    long losses = fullDrops.get() + shutdownDrops.get() + unconfirmed.get();
    long now = System.nanoTime();
    if (losses != lastWarningLosses && now - lastWarningNanos >= Duration.ofSeconds(10).toNanos()) {
      LOG.warn(
          "Analytics recording incomplete: queueFull={}, shutdown={}, unconfirmedWrites={}",
          fullDrops.get(),
          shutdownDrops.get(),
          unconfirmed.get());
      lastWarningLosses = losses;
      lastWarningNanos = now;
    }
  }

  private void discardQueued() {
    var discarded = new ArrayList<Event>();
    queue.drainTo(discarded);
    shutdownDrops.addAndGet(discarded.size());
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    long deadline = System.nanoTime() + settings.shutdownTimeout().toNanos();
    synchronized (admission) {
      accepting = false;
    }
    boolean interrupted = false;
    try {
      worker.join(settings.drainTimeout());
    } catch (InterruptedException failure) {
      interrupted = true;
    } finally {
      abandon = true;
      discardQueued();
      worker.interrupt();
      // JDBC cleanup may abort an in-flight write. Bound shutdown even if a driver misbehaves.
      var cleanup =
          Thread.ofPlatform().name("redirect-analytics-cleanup").daemon(true).start(writer::close);
      try {
        worker.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        cleanup.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        if (worker.isAlive() || cleanup.isAlive()) {
          LOG.warn("Analytics shutdown deadline reached; background cleanup still pending");
        }
      } catch (InterruptedException failure) {
        interrupted = true;
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
