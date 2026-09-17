package com.jyshnkr.urlshortener.performance;

import com.jyshnkr.urlshortener.links.analytics.RedirectRecorder.Diagnostics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

  record Analytics(
      long expectedRedirects,
      long persistedRedirects,
      long mismatchedLinks,
      double drainSeconds,
      boolean drained,
      Diagnostics diagnostics,
      String readFailure,
      boolean passed) {}

  static Analytics summarizeAnalytics(
      Map<String, Long> expected,
      Map<String, Long> persisted,
      double drainSeconds,
      boolean drained,
      Diagnostics diagnostics,
      String readFailure) {
    long expectedTotal = expected.values().stream().mapToLong(Long::longValue).sum();
    long persistedTotal = persisted.values().stream().mapToLong(Long::longValue).sum();
    long mismatches =
        expected.entrySet().stream()
            .filter(entry -> !entry.getValue().equals(persisted.getOrDefault(entry.getKey(), 0L)))
            .count();
    return new Analytics(
        expectedTotal,
        persistedTotal,
        mismatches,
        drainSeconds,
        drained,
        diagnostics,
        readFailure,
        expectedTotal > 0
            && drained
            && readFailure.isEmpty()
            && mismatches == 0
            && expectedTotal == persistedTotal
            && diagnostics.submitted() == expectedTotal
            && diagnostics.confirmedWrites() == expectedTotal
            && diagnostics.queueFullDrops() == 0
            && diagnostics.shutdownDrops() == 0
            && diagnostics.unconfirmedWrites() == 0
            && diagnostics.queued() == 0
            && diagnostics.inFlight() == 0
            && diagnostics.workerAlive());
  }

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
    write(directory, metadata, attempts, summary, null);
  }

  static void write(
      Path directory,
      Map<String, Object> metadata,
      List<Attempt> attempts,
      Summary summary,
      Analytics analytics)
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
    var report = new LinkedHashMap<String, Object>();
    report.put("metadata", metadata);
    report.put("summary", summary);
    if (analytics != null) {
      report.put("analytics", analytics);
    }
    report.put("passed", summary.passed() && (analytics == null || analytics.passed()));
    Files.writeString(
        directory.resolve("report.json"),
        JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report)
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
    if (analytics != null) {
      console +=
          String.format(
              Locale.ROOT,
              "Analytics %s: expected=%d, persisted=%d, mismatchedLinks=%d, drain=%.6f s, drained=%s;%n"
                  + "diagnostics=%s, readFailure=%s; overall=%s%n",
              analytics.passed() ? "PASS" : "FAIL",
              analytics.expectedRedirects(),
              analytics.persistedRedirects(),
              analytics.mismatchedLinks(),
              analytics.drainSeconds(),
              analytics.drained(),
              analytics.diagnostics(),
              analytics.readFailure(),
              report.get("passed"));
    }
    Files.writeString(directory.resolve("summary.txt"), console);
    System.out.print(console);
  }

  private RedirectPerformanceReport() {}
}
