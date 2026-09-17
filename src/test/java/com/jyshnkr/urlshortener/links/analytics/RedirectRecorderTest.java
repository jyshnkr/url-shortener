package com.jyshnkr.urlshortener.links.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jyshnkr.urlshortener.links.model.RedirectIncrement;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RedirectRecorderTest {
  private static final class CloseableMeters extends SimpleMeterRegistry implements AutoCloseable {}

  private static final Duration WAIT = Duration.ofSeconds(2);
  private static final RecordingSettings SMALL =
      new RecordingSettings(
          1, 1, Duration.ofMillis(10), Duration.ofMillis(100), Duration.ofSeconds(1));

  @Test
  void fullQueueNeverWaitsForTheStalledWriterAndReportsDroppedEvents() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var saved = new CopyOnWriteArrayList<RedirectIncrement>();
    var closed = new AtomicBoolean();
    var writer =
        new AnalyticsBatchWriter() {
          public void write(List<RedirectIncrement> batch) {
            entered.countDown();
            waitFor(release);
            saved.addAll(batch);
          }

          public void close() {
            closed.set(true);
            release.countDown();
          }
        };
    try (var metrics = new CloseableMeters();
        var recorder = new RedirectRecorder(writer, metrics, Clock.systemUTC(), SMALL)) {
      recorder.record("FirstCode1");
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      recorder.record("SecondCode");
      assertTimeoutPreemptively(Duration.ofMillis(200), () -> recorder.record("Dropped001"));
      assertThat(recorder.diagnostics().queueFullDrops()).isEqualTo(1);
      assertThat(recorder.diagnostics().queued()).isEqualTo(1);
      assertThat(
              metrics
                  .get("shortener.analytics.events")
                  .tag("outcome", "queue_full")
                  .functionCounter()
                  .count())
          .isEqualTo(1);
      release.countDown();
      await()
          .atMost(WAIT)
          .untilAsserted(() -> assertThat(recorder.diagnostics().confirmedWrites()).isEqualTo(2));
      assertThat(saved)
          .extracting(RedirectIncrement::code)
          .containsExactly("FirstCode1", "SecondCode");
    } finally {
      release.countDown();
    }
    assertThat(closed).isTrue();
  }

  @Test
  void failedBatchIsNotRetriedAndLaterEventsCanStillBeRecorded() {
    var fail = new AtomicBoolean(true);
    var saved = new CopyOnWriteArrayList<RedirectIncrement>();
    var writer =
        new AnalyticsBatchWriter() {
          public void write(List<RedirectIncrement> batch) {
            if (fail.get()) {
              throw new IllegalStateException("Simulated uncertain write");
            }
            saved.addAll(batch);
          }

          public void close() {}
        };
    try (var metrics = new CloseableMeters();
        var recorder = new RedirectRecorder(writer, metrics, Clock.systemUTC(), SMALL)) {
      recorder.record("Failed0001");
      await()
          .atMost(WAIT)
          .untilAsserted(() -> assertThat(recorder.diagnostics().unconfirmedWrites()).isEqualTo(1));
      fail.set(false);
      recorder.record("Success001");
      await()
          .atMost(WAIT)
          .untilAsserted(() -> assertThat(recorder.diagnostics().confirmedWrites()).isEqualTo(1));
      assertThat(saved).extracting(RedirectIncrement::code).containsExactly("Success001");
      assertThat(recorder.diagnostics().unconfirmedWrites()).isEqualTo(1);
    }
  }

  @Test
  void aBatchCombinesRepeatedCodesAndKeepsTheNewestEventTime() {
    var saved = new CopyOnWriteArrayList<RedirectIncrement>();
    var clock = mock(Clock.class);
    Instant earlier = Instant.parse("2026-09-17T10:00:00Z");
    Instant later = earlier.plusSeconds(10);
    when(clock.instant()).thenReturn(later, earlier, later);
    var writer =
        new AnalyticsBatchWriter() {
          public void write(List<RedirectIncrement> batch) {
            saved.addAll(batch);
          }

          public void close() {}
        };
    var settings =
        new RecordingSettings(
            3, 3, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3));
    try (var metrics = new CloseableMeters();
        var recorder = new RedirectRecorder(writer, metrics, clock, settings)) {
      recorder.record("Repeat0001");
      recorder.record("Repeat0001");
      recorder.record("Another001");
      await()
          .atMost(WAIT)
          .untilAsserted(() -> assertThat(recorder.diagnostics().confirmedWrites()).isEqualTo(3));
      assertThat(saved)
          .containsExactly(
              new RedirectIncrement("Another001", 1, later),
              new RedirectIncrement("Repeat0001", 2, later));
    }
  }

  @Test
  void gracefulCloseFlushesAPartialBatchAndStopsTheWorker() {
    var saved = new CopyOnWriteArrayList<RedirectIncrement>();
    var writerClosed = new AtomicBoolean();
    var writer =
        new AnalyticsBatchWriter() {
          public void write(List<RedirectIncrement> batch) {
            saved.addAll(batch);
          }

          public void close() {
            writerClosed.set(true);
          }
        };
    try (var metrics = new CloseableMeters()) {
      var recorder =
          new RedirectRecorder(writer, metrics, Clock.systemUTC(), RecordingSettings.defaults());
      recorder.record("Saved00001");
      recorder.close();
      recorder.close();
      assertThat(saved).extracting(RedirectIncrement::count).containsExactly(1L);
      assertThat(recorder.diagnostics().workerAlive()).isFalse();
      assertThat(writerClosed).isTrue();
    }
  }

  @Test
  void shutdownDiscardsBacklogAndRejectsLaterEventsWithinTheDeadline() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var writerClosed = new AtomicBoolean();
    var writer =
        new AnalyticsBatchWriter() {
          public void write(List<RedirectIncrement> batch) {
            entered.countDown();
            waitFor(release);
          }

          public void close() {
            writerClosed.set(true);
            release.countDown();
          }
        };
    try (var metrics = new CloseableMeters();
        var recorder = new RedirectRecorder(writer, metrics, Clock.systemUTC(), SMALL)) {
      recorder.record("InFlight01");
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      recorder.record("Queued0001");
      assertTimeoutPreemptively(WAIT, recorder::close);
      recorder.record("Stopped001");
      assertThat(recorder.diagnostics().shutdownDrops()).isEqualTo(2);
      assertThat(recorder.diagnostics().workerAlive()).isFalse();
      assertThat(writerClosed).isTrue();
    } finally {
      release.countDown();
    }
  }

  @Test
  void evenStalledCleanupCannotExtendTheShutdownDeadline() {
    var release = new CountDownLatch(1);
    var finished = new AtomicBoolean();
    var writer =
        new AnalyticsBatchWriter() {
          public void write(List<RedirectIncrement> batch) {}

          public void close() {
            waitFor(release);
            finished.set(true);
          }
        };
    var limits =
        new RecordingSettings(
            1, 1, Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(200));
    try (var metrics = new CloseableMeters()) {
      var recorder = new RedirectRecorder(writer, metrics, Clock.systemUTC(), limits);
      try {
        assertTimeoutPreemptively(Duration.ofSeconds(1), recorder::close);
      } finally {
        release.countDown();
      }
      await().atMost(WAIT).untilTrue(finished);
    }
  }

  private static void waitFor(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Test gate expired");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Write interrupted", interrupted);
    }
  }
}
