package com.jyshnkr.urlshortener.performance;

import static org.assertj.core.api.Assertions.assertThat;

import com.jyshnkr.urlshortener.links.analytics.RedirectRecorder.Diagnostics;
import com.jyshnkr.urlshortener.performance.RedirectPerformanceReport.Attempt;
import com.jyshnkr.urlshortener.performance.RedirectPerformanceReport.Outcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class RedirectPerformanceReportTest {

  @Test
  void analyticsRequiresExactPerCodeCountsAndNoLossesOrPendingWrites() {
    var expected = Map.of("one", 2L, "two", 3L);
    var good = new Diagnostics(5, 5, 0, 0, 0, 0, 0, true);
    assertThat(
            RedirectPerformanceReport.summarizeAnalytics(expected, expected, 0.1, true, good, "")
                .passed())
        .isTrue();
    assertThat(
            RedirectPerformanceReport.summarizeAnalytics(
                    expected, Map.of("one", 3L, "two", 2L), 0.1, true, good, "")
                .passed())
        .isFalse();
    assertThat(
            RedirectPerformanceReport.summarizeAnalytics(expected, expected, 10, false, good, "")
                .passed())
        .isFalse();
    assertThat(
            RedirectPerformanceReport.summarizeAnalytics(
                    expected, Map.of(), 0.1, true, good, "DataAccessException")
                .passed())
        .isFalse();
    for (var diagnostics :
        List.of(
            new Diagnostics(6, 5, 1, 0, 0, 0, 0, true),
            new Diagnostics(6, 5, 0, 1, 0, 0, 0, true),
            new Diagnostics(6, 5, 0, 0, 1, 0, 0, true),
            new Diagnostics(5, 4, 0, 0, 0, 1, 0, true),
            new Diagnostics(5, 4, 0, 0, 0, 0, 1, true),
            new Diagnostics(5, 5, 0, 0, 0, 0, 0, false))) {
      assertThat(
              RedirectPerformanceReport.summarizeAnalytics(
                      expected, expected, 0.1, true, diagnostics, "")
                  .passed())
          .isFalse();
    }
  }

  @Test
  void failedAnalyticsMakesTheOverallReportFailEvenWhenRedirectsPass(@TempDir Path directory)
      throws Exception {
    var attempts = List.of(valid(1));
    var analytics =
        RedirectPerformanceReport.summarizeAnalytics(
            Map.of("one", 1L), Map.of(), 10, false, new Diagnostics(1, 0, 0, 0, 1, 0, 0, true), "");
    RedirectPerformanceReport.write(directory, Map.of(), attempts, summarize(attempts), analytics);
    var report =
        JsonMapper.builder().build().readTree(Files.readString(directory.resolve("report.json")));
    assertThat(report.get("passed").booleanValue()).isFalse();
    assertThat(report.get("summary").get("passed").booleanValue()).isTrue();
    assertThat(report.get("analytics").get("expectedRedirects").longValue()).isEqualTo(1);
    assertThat(report.get("analytics").get("diagnostics").get("unconfirmedWrites").longValue())
        .isEqualTo(1);
    assertThat(Files.readString(directory.resolve("summary.txt")))
        .contains("Analytics FAIL", "overall=false");
  }

  @Test
  void reportsRetainFailedSamplesAndMachineReadableSummary(@TempDir Path directory)
      throws Exception {
    var attempts = List.of(new Attempt(2, 17, 50, 10_000_000_000L, 0, Outcome.TIMEOUT, "a,\"b\""));
    RedirectPerformanceReport.write(
        directory, Map.of("gitWorkingTreeState", "dirty"), attempts, summarize(attempts));
    var report =
        JsonMapper.builder().build().readTree(Files.readString(directory.resolve("report.json")));
    assertThat(report.get("summary").get("passed").booleanValue()).isFalse();
    assertThat(report.get("summary").get("attempts").intValue()).isEqualTo(1);
    assertThat(report.get("summary").get("timeouts").intValue()).isEqualTo(1);
    assertThat(report.get("summary").get("p95Ms").doubleValue()).isEqualTo(10_000);
    assertThat(report.get("metadata").get("gitWorkingTreeState").stringValue()).isEqualTo("dirty");
    assertThat(Files.readString(directory.resolve("requests.csv")))
        .isEqualTo(
            "client,link,start_offset_ns,latency_ns,status,outcome,detail\n2,17,50,10000000000,0,TIMEOUT,\"a,\"\"b\"\"\"\n");
    assertThat(Files.readString(directory.resolve("summary.txt"))).contains("FAIL", "timeouts=1");
  }

  @Test
  void percentilesUseNearestRankIncludingSmallAndUnsortedSamples() {
    var summary = summarize(List.of(valid(90), valid(10), valid(50), valid(30)));
    assertThat(summary.p50Ms()).isEqualTo(30);
    assertThat(summary.p95Ms()).isEqualTo(90);
    assertThat(summary.p99Ms()).isEqualTo(90);
    assertThat(summary.maximumMs()).isEqualTo(90);
    var hundred = new ArrayList<Attempt>();
    for (int ms = 100; ms >= 1; ms--) {
      hundred.add(valid(ms));
    }
    var many = summarize(hundred);
    assertThat(many.p50Ms()).isEqualTo(50);
    assertThat(many.p95Ms()).isEqualTo(95);
    assertThat(many.p99Ms()).isEqualTo(99);
  }

  @Test
  void exactly95PercentAtOrBelow100MsPassesButOneMoreSlowResponseFails() {
    var attempts = new ArrayList<Attempt>();
    for (int index = 0; index < 19; index++) {
      attempts.add(valid(100));
    }
    attempts.add(valid(101));
    var boundary = summarize(attempts);
    assertThat(boundary.percentageWithin100Ms()).isEqualTo(95);
    assertThat(boundary.passed()).isTrue();
    attempts.set(0, valid(101));
    assertThat(summarize(attempts).passed()).isFalse();
    assertThat(summarize(attempts).percentageWithin100Ms()).isEqualTo(90);
  }

  @Test
  void fastIncorrectResponsesAndRequestErrorsCannotMakeTheRunPass() {
    for (var outcome : List.of(Outcome.INCORRECT_RESPONSE, Outcome.REQUEST_ERROR)) {
      var summary =
          summarize(List.of(valid(1), new Attempt(0, 0, 0, 1_000_000, 500, outcome, "failure")));
      assertThat(summary.percentageWithin100Ms()).isEqualTo(100);
      assertThat(summary.validRedirects()).isEqualTo(1);
      assertThat(summary.errors()).isEqualTo(1);
      assertThat(summary.timeouts()).isZero();
      assertThat(summary.passed()).isFalse();
    }
  }

  @Test
  void timeoutsRemainInTheDenominatorAndLatencyDistribution() {
    var summary =
        summarize(
            List.of(
                valid(1), new Attempt(0, 1, 0, 10_000_000_000L, 0, Outcome.TIMEOUT, "timeout")));
    assertThat(summary.attempts()).isEqualTo(2);
    assertThat(summary.errors()).isEqualTo(1);
    assertThat(summary.timeouts()).isEqualTo(1);
    assertThat(summary.percentageWithin100Ms()).isEqualTo(50);
    assertThat(summary.p95Ms()).isEqualTo(10_000);
    assertThat(summary.passed()).isFalse();
  }

  @Test
  void finalOutstandingResponseCountsAndThroughputIncludesItsDrainTime() {
    var summary =
        summarize(
            List.of(new Attempt(0, 0, 59_999_000_000L, 2_001_000_000L, 302, Outcome.VALID, "")));
    assertThat(summary.measurementSeconds()).isEqualTo(60);
    assertThat(summary.finalDrainSeconds()).isEqualTo(2);
    assertThat(summary.completedRequestsPerSecond()).isEqualTo(1.0 / 62);
    assertThat(summary.validRedirects()).isEqualTo(1);
  }

  @Test
  void emptyResultsFailWithoutInventingLatencies() {
    var summary = summarize(List.of());
    assertThat(summary.attempts()).isZero();
    assertThat(summary.percentageWithin100Ms()).isZero();
    assertThat(summary.completedRequestsPerSecond()).isZero();
    assertThat(summary.p50Ms()).isNull();
    assertThat(summary.p95Ms()).isNull();
    assertThat(summary.p99Ms()).isNull();
    assertThat(summary.maximumMs()).isNull();
    assertThat(summary.passed()).isFalse();
  }

  private static Attempt valid(long milliseconds) {
    return new Attempt(0, 0, 0, milliseconds * 1_000_000, 302, Outcome.VALID, "");
  }

  private static RedirectPerformanceReport.Summary summarize(List<Attempt> attempts) {
    return RedirectPerformanceReport.summarize(attempts, 60_000_000_000L);
  }
}
