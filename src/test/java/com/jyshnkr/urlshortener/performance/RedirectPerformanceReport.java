package com.jyshnkr.urlshortener.performance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

final class RedirectPerformanceReport {

  static final long LATENCY_LIMIT_NANOS = 100_000_000L;

  enum Outcome {
    VALID,
    INCORRECT_RESPONSE,
    REQUEST_ERROR,
    TIMEOUT
  }

  record Attempt(
      int client,
      int link,
      long startedNanos,
      long latencyNanos,
      int status,
      Outcome outcome,
      String detail) {}

  record Summary(
      int attempts,
      long validRedirects,
      long errors,
      long timeouts,
      long within100Ms,
      double percentageWithin100Ms,
      Double p50Ms,
      Double p95Ms,
      Double p99Ms,
      Double maximumMs,
      double measurementSeconds,
      double finalDrainSeconds,
      double completedRequestsPerSecond,
      boolean passed) {}

  static Summary summarize(List<Attempt> attempts, long measurementNanos) {
    if (measurementNanos <= 0) {
      throw new IllegalArgumentException("Measurement interval must be positive");
    }
    long[] latencies = attempts.stream().mapToLong(Attempt::latencyNanos).sorted().toArray();
    long valid = attempts.stream().filter(a -> a.outcome() == Outcome.VALID).count();
    long timeouts = attempts.stream().filter(a -> a.outcome() == Outcome.TIMEOUT).count();
    long fast = attempts.stream().filter(a -> a.latencyNanos() <= LATENCY_LIMIT_NANOS).count();
    long lastCompletion =
        attempts.stream()
            .mapToLong(a -> a.startedNanos() + a.latencyNanos())
            .max()
            .orElse(measurementNanos);
    long drainNanos = Math.max(0, lastCompletion - measurementNanos);
    long errors = attempts.size() - valid;
    return new Summary(
        attempts.size(),
        valid,
        errors,
        timeouts,
        fast,
        attempts.isEmpty() ? 0 : 100.0 * fast / attempts.size(),
        percentileMs(latencies, 50),
        percentileMs(latencies, 95),
        percentileMs(latencies, 99),
        percentileMs(latencies, 100),
        measurementNanos / 1_000_000_000.0,
        drainNanos / 1_000_000_000.0,
        attempts.size() / ((measurementNanos + drainNanos) / 1_000_000_000.0),
        !attempts.isEmpty() && errors == 0 && fast * 100 >= attempts.size() * 95L);
  }

  private static Double percentileMs(long[] sorted, int percentile) {
    if (sorted.length == 0) {
      return null;
    }
    // Nearest rank: ceil(percentile * count / 100), converted to a zero-based index.
    int rank = (int) ((percentile * (long) sorted.length + 99) / 100);
    return sorted[rank - 1] / 1_000_000.0;
  }

  static void write(
      Path directory, Map<String, Object> metadata, List<Attempt> attempts, Summary summary)
      throws IOException {
    Files.createDirectories(directory);
    try (var csv = Files.newBufferedWriter(directory.resolve("requests.csv"))) {
      csv.write("client,link,start_offset_ns,latency_ns,status,outcome,detail\n");
      for (var attempt :
          attempts.stream().sorted(Comparator.comparingLong(Attempt::startedNanos)).toList()) {
        csv.write(
            attempt.client()
                + ","
                + attempt.link()
                + ","
                + attempt.startedNanos()
                + ","
                + attempt.latencyNanos()
                + ","
                + attempt.status()
                + ","
                + attempt.outcome()
                + ",\""
                + attempt.detail().replace("\"", "\"\"")
                + "\"\n");
      }
    }
    Files.writeString(
        directory.resolve("report.json"),
        JsonMapper.builder()
                .build()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("metadata", metadata, "summary", summary))
            + "\n");
    String console =
        String.format(
            Locale.ROOT,
            "Redirect performance %s: attempts=%d, valid=%d, errors=%d, timeouts=%d, within100ms=%.6f%%;%n"
                + "p50=%s ms, p95=%s ms, p99=%s ms, max=%s ms; completed/s=%.3f, interval=%.3f s, drain=%.6f s%n"
                + "Reports: %s%n",
            summary.passed() ? "PASS" : "FAIL",
            summary.attempts(),
            summary.validRedirects(),
            summary.errors(),
            summary.timeouts(),
            summary.percentageWithin100Ms(),
            summary.p50Ms(),
            summary.p95Ms(),
            summary.p99Ms(),
            summary.maximumMs(),
            summary.completedRequestsPerSecond(),
            summary.measurementSeconds(),
            summary.finalDrainSeconds(),
            directory);
    Files.writeString(directory.resolve("summary.txt"), console);
    System.out.print(console);
  }

  private RedirectPerformanceReport() {}
}
