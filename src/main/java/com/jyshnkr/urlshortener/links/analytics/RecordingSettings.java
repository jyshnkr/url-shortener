package com.jyshnkr.urlshortener.links.analytics;

import java.time.Duration;
import java.util.Objects;

public record RecordingSettings(
    int queueCapacity,
    int batchSize,
    Duration flushInterval,
    Duration drainTimeout,
    Duration shutdownTimeout) {
  public RecordingSettings {
    Objects.requireNonNull(flushInterval);
    Objects.requireNonNull(drainTimeout);
    Objects.requireNonNull(shutdownTimeout);
    if (queueCapacity <= 0
        || batchSize <= 0
        || batchSize > queueCapacity
        || flushInterval.isNegative()
        || flushInterval.isZero()
        || drainTimeout.isNegative()
        || drainTimeout.isZero()
        || shutdownTimeout.compareTo(drainTimeout) <= 0) {
      throw new IllegalArgumentException("Invalid analytics recording limits");
    }
  }

  public static RecordingSettings defaults() {
    return new RecordingSettings(
        10_000, 1_000, Duration.ofMillis(250), Duration.ofSeconds(5), Duration.ofSeconds(15));
  }
}
