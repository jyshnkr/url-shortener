package com.jyshnkr.urlshortener.performance;

import static org.assertj.core.api.Assertions.assertThat;

import com.jyshnkr.urlshortener.UrlShortenerApplication;
import com.jyshnkr.urlshortener.links.analytics.KafkaRedirectPublisher;
import com.jyshnkr.urlshortener.links.analytics.RecordingSettings;
import com.jyshnkr.urlshortener.links.analytics.RedirectEventConsumer;
import com.jyshnkr.urlshortener.links.analytics.RedirectRecorder;
import com.jyshnkr.urlshortener.performance.RedirectPerformanceReport.Attempt;
import com.jyshnkr.urlshortener.performance.RedirectPerformanceReport.Outcome;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.apache.kafka.common.utils.AppInfoParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

class RedirectPerformanceIT {

  private static final int LINKS = 1_000;
  private static final int CLIENTS = 10;
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration WARM_UP = Duration.ofSeconds(15);
  private static final Duration MEASUREMENT = Duration.ofSeconds(60);
  private static final Duration RECORDING_DRAIN_LIMIT = Duration.ofSeconds(10);
  private static final String KAFKA_IMAGE = "apache/kafka:4.2.2";

  /** kafka (the application default) or in-process, which reproduces the earlier runs. */
  private static final String TRANSPORT =
      System.getProperty("performance.analytics.transport", "kafka");

  private record SavedLink(HttpRequest request, String destination, String code) {}

  private record Phase(Instant timestamp, List<Attempt> attempts) {}

  private record Persisted(Map<String, Long> counts, String readFailure) {}

  @Test
  @Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void atLeast95PercentOfRedirectsCompleteWithin100MsWithoutErrors() throws Exception {
    assertThat(TRANSPORT).as("performance.analytics.transport").isIn("kafka", "in-process");
    boolean kafka = "kafka".equals(TRANSPORT);
    var metadata = environment();
    try (var database = new PostgreSQLContainer("postgres:17.11");
        var broker = kafka ? new KafkaContainer(KAFKA_IMAGE) : null) {
      database.withStartupTimeout(Duration.ofSeconds(60));
      database.start();
      var arguments =
          new ArrayList<>(
              List.of(
                  "--server.port=0",
                  "--server.address=127.0.0.1",
                  "--spring.datasource.url=" + database.getJdbcUrl(),
                  "--spring.datasource.username=" + database.getUsername(),
                  "--spring.datasource.password=" + database.getPassword(),
                  "--shortener.analytics.transport=" + TRANSPORT));
      if (broker != null) {
        broker.withStartupTimeout(Duration.ofSeconds(60));
        broker.start();
        arguments.add("--spring.kafka.bootstrap-servers=" + broker.getBootstrapServers());
        metadata.put("kafkaImage", broker.getDockerImageName());
        metadata.put("kafkaClientVersion", AppInfoParser.getVersion());
      }
      try (var application =
          new SpringApplicationBuilder(UrlShortenerApplication.class)
              .run(arguments.toArray(String[]::new))) {
        metadata.put(
            "postgresqlVersion",
            application
                .getBean(JdbcTemplate.class)
                .queryForObject("SELECT version()", String.class));
        metadata.put("postgresqlImage", database.getDockerImageName());
        metadata.put(
            "analyticsSettings",
            kafka ? kafkaSettings(application.getEnvironment()) : RecordingSettings.defaults());
        int port = ((WebServerApplicationContext) application).getWebServer().getPort();
        var clients = new ArrayList<HttpClient>();
        var workers = Executors.newFixedThreadPool(CLIENTS);
        try {
          for (int index = 0; index < CLIENTS; index++) {
            clients.add(
                HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build());
          }
          var links = createLinks(clients.getFirst(), port);
          System.out.println(
              "Created 1,000 distinct links; warming ten persistent clients for 15 seconds.");
          var warmUp = runPhase(workers, clients, links, WARM_UP);
          assertThat(warmUp.attempts()).isNotEmpty().allMatch(a -> a.outcome() == Outcome.VALID);
          System.out.println("Warm-up complete; measuring ten clients for 60 seconds.");
          var measured = runPhase(workers, clients, links, MEASUREMENT);
          var expected = expectedCounts(links, warmUp, measured);
          var source = application.getBean(DataSource.class);
          var analytics =
              kafka
                  ? verifyKafkaAnalytics(
                      application.getBean(KafkaRedirectPublisher.class),
                      application.getBean(RedirectEventConsumer.class),
                      source,
                      expected)
                  : verifyQueueAnalytics(
                      application.getBean(RedirectRecorder.class), source, expected);
          metadata.put("measurementStartedAt", measured.timestamp().toString());
          var summary =
              RedirectPerformanceReport.summarize(measured.attempts(), MEASUREMENT.toNanos());
          String runName =
              DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
                      .withZone(ZoneOffset.UTC)
                      .format(measured.timestamp())
                  + "-"
                  + UUID.randomUUID();
          var directory = Path.of("target", "performance", runName);
          RedirectPerformanceReport.write(
              directory, metadata, measured.attempts(), summary, analytics);
          assertThat(summary.passed()).as("Local redirect target; see %s", directory).isTrue();
          assertThat(analytics.passed()).as("Analytics completeness; see %s", directory).isTrue();
        } finally {
          // Initiate every shutdown before waiting, so one stalled client cannot block the others.
          workers.shutdownNow();
          clients.forEach(HttpClient::shutdownNow);
          long cleanupDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
          try {
            for (var client : clients) {
              client.awaitTermination(
                  Duration.ofNanos(Math.max(0, cleanupDeadline - System.nanoTime())));
            }
            workers.awaitTermination(
                Math.max(0, cleanupDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        }
      }
    }
  }

  private List<SavedLink> createLinks(HttpClient client, int port) throws Exception {
    var json = JsonMapper.builder().build();
    var links = new ArrayList<SavedLink>();
    var codes = new HashSet<String>();
    for (int index = 0; index < LINKS; index++) {
      String destination = "https://example.invalid/performance/" + index;
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/links"))
              .timeout(REQUEST_TIMEOUT)
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      json.createObjectNode().put("destinationUrl", destination).toString()))
              .build();
      var response = send(client, request);
      assertThat(response.statusCode()).isEqualTo(201);
      var created = json.readTree(response.body());
      assertThat(created.get("destinationUrl").stringValue()).isEqualTo(destination);
      String code = created.get("code").stringValue();
      assertThat(code).matches("[A-Za-z0-9]{10}");
      assertThat(codes.add(code)).as("Distinct saved code for link %d", index).isTrue();
      links.add(
          new SavedLink(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/r/" + code))
                  .timeout(REQUEST_TIMEOUT)
                  .GET()
                  .build(),
              destination,
              code));
    }
    return List.copyOf(links);
  }

  private Map<String, Long> expectedCounts(List<SavedLink> links, Phase warmUp, Phase measured) {
    var expected = new HashMap<String, Long>();
    links.forEach(link -> expected.put(link.code(), 0L));
    for (var phase : List.of(warmUp, measured)) {
      for (var attempt : phase.attempts()) {
        if (attempt.outcome() == Outcome.VALID) {
          expected.merge(links.get(attempt.link()).code(), 1L, Long::sum);
        }
      }
    }
    return expected;
  }

  private RedirectPerformanceReport.Analytics verifyQueueAnalytics(
      RedirectRecorder recorder, DataSource source, Map<String, Long> expected)
      throws InterruptedException {
    long total = expected.values().stream().mapToLong(Long::longValue).sum();
    long before = System.nanoTime();
    long deadline = before + RECORDING_DRAIN_LIMIT.toNanos();
    var diagnostics = recorder.diagnostics();
    while (System.nanoTime() < deadline
        && (diagnostics.confirmedWrites() < total
            || diagnostics.queued() != 0
            || diagnostics.inFlight() != 0)) {
      TimeUnit.MILLISECONDS.sleep(10);
      diagnostics = recorder.diagnostics();
    }
    double drainSeconds = (System.nanoTime() - before) / 1_000_000_000.0;
    boolean drained =
        diagnostics.confirmedWrites() == total
            && diagnostics.queued() == 0
            && diagnostics.inFlight() == 0
            && System.nanoTime() <= deadline;
    var persisted = readPersisted(source);
    return RedirectPerformanceReport.summarizeAnalytics(
        expected, persisted.counts(), drainSeconds, drained, diagnostics, persisted.readFailure());
  }

  private RedirectPerformanceReport.Analytics verifyKafkaAnalytics(
      KafkaRedirectPublisher publisher,
      RedirectEventConsumer consumer,
      DataSource source,
      Map<String, Long> expected)
      throws InterruptedException {
    long total = expected.values().stream().mapToLong(Long::longValue).sum();
    long before = System.nanoTime();
    long deadline = before + RECORDING_DRAIN_LIMIT.toNanos();
    var diagnostics =
        new RedirectPerformanceReport.KafkaDiagnostics(
            publisher.diagnostics(), consumer.diagnostics());
    while (System.nanoTime() < deadline
        && (diagnostics.publisher().pending() != 0 || diagnostics.consumer().recorded() < total)) {
      TimeUnit.MILLISECONDS.sleep(10);
      diagnostics =
          new RedirectPerformanceReport.KafkaDiagnostics(
              publisher.diagnostics(), consumer.diagnostics());
    }
    double drainSeconds = (System.nanoTime() - before) / 1_000_000_000.0;
    boolean drained =
        diagnostics.publisher().pending() == 0
            && diagnostics.consumer().recorded() == total
            && System.nanoTime() <= deadline;
    var persisted = readPersisted(source);
    return RedirectPerformanceReport.summarizeKafkaAnalytics(
        expected, persisted.counts(), drainSeconds, drained, diagnostics, persisted.readFailure());
  }

  private Persisted readPersisted(DataSource source) {
    var persisted = new HashMap<String, Long>();
    try {
      // Verification reads happen outside HTTP measurement and after the bounded analytics drain.
      var jdbc = new JdbcTemplate(source);
      jdbc.setQueryTimeout(3);
      jdbc.query(
          "SELECT short_code, redirect_count FROM link_analytics",
          row -> {
            persisted.put(row.getString("short_code"), row.getLong("redirect_count"));
          });
    } catch (RuntimeException readFailure) {
      return new Persisted(persisted, readFailure.getClass().getSimpleName());
    }
    return new Persisted(persisted, "");
  }

  private static Map<String, Object> kafkaSettings(Environment environment) {
    var settings = new LinkedHashMap<String, Object>();
    for (String key :
        List.of(
            "shortener.analytics.kafka.topic",
            "spring.kafka.producer.acks",
            "spring.kafka.producer.properties[enable.idempotence]",
            "spring.kafka.producer.properties[max.block.ms]",
            "spring.kafka.consumer.max-poll-records",
            "spring.kafka.listener.ack-mode")) {
      settings.put(key, environment.getProperty(key));
    }
    return settings;
  }

  private Phase runPhase(
      ExecutorService workers, List<HttpClient> clients, List<SavedLink> links, Duration duration)
      throws Exception {
    var nextLink = new AtomicInteger();
    var start = new AtomicLong();
    var timestamp = new Instant[1];
    var barrier =
        new CyclicBarrier(
            CLIENTS,
            () -> {
              timestamp[0] = Instant.now();
              start.set(System.nanoTime());
            });
    var pending = new ArrayList<Future<List<Attempt>>>();
    // Includes a bounded barrier rendezvous, interval and a 15-second drain allowance.
    long completionDeadline = System.nanoTime() + duration.plusSeconds(30).toNanos();
    try {
      for (int index = 0; index < CLIENTS; index++) {
        int clientIndex = index;
        pending.add(
            workers.submit(
                () -> {
                  var attempts = new ArrayList<Attempt>();
                  barrier.await(15, TimeUnit.SECONDS);
                  long deadline = start.get() + duration.toNanos();
                  while (!Thread.currentThread().isInterrupted()) {
                    int linkIndex = Math.floorMod(nextLink.getAndIncrement(), links.size());
                    var link = links.get(linkIndex);
                    var client = clients.get(clientIndex);
                    long before = System.nanoTime();
                    if (before >= deadline) {
                      break;
                    }
                    attempts.add(
                        attempt(client, link, clientIndex, linkIndex, before, start.get()));
                  }
                  return attempts;
                }));
      }
      var attempts = new ArrayList<Attempt>();
      for (var future : pending) {
        attempts.addAll(
            future.get(Math.max(1, completionDeadline - System.nanoTime()), TimeUnit.NANOSECONDS));
      }
      return new Phase(timestamp[0], attempts);
    } finally {
      pending.forEach(future -> future.cancel(true));
    }
  }

  private Attempt attempt(
      HttpClient client,
      SavedLink link,
      int clientIndex,
      int linkIndex,
      long before,
      long phaseStart) {
    try {
      var response = send(client, link.request());
      long latency = System.nanoTime() - before;
      boolean valid =
          response.statusCode() == 302
              && response.headers().allValues("Location").equals(List.of(link.destination()))
              && response.headers().allValues("Cache-Control").equals(List.of("no-store"))
              && response.body().length == 0;
      return new Attempt(
          clientIndex,
          linkIndex,
          before - phaseStart,
          latency,
          response.statusCode(),
          valid ? Outcome.VALID : Outcome.INCORRECT_RESPONSE,
          valid
              ? ""
              : "Expected 302, exact Location, no-store and empty body; location="
                  + response.headers().allValues("Location")
                  + "; cacheControl="
                  + response.headers().allValues("Cache-Control")
                  + "; bodyBytes="
                  + response.body().length);
    } catch (Exception failure) {
      long latency = System.nanoTime() - before;
      Throwable cause = failure instanceof ExecutionException ? failure.getCause() : failure;
      boolean timeout = cause instanceof HttpTimeoutException || cause instanceof TimeoutException;
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return new Attempt(
          clientIndex,
          linkIndex,
          before - phaseStart,
          latency,
          0,
          timeout ? Outcome.TIMEOUT : Outcome.REQUEST_ERROR,
          cause.getClass().getSimpleName());
    }
  }

  private HttpResponse<byte[]> send(HttpClient client, HttpRequest request) throws Exception {
    CompletableFuture<HttpResponse<byte[]>> pending =
        client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
    try {
      // Also bounds full-body receipt, rather than relying only on the request/header timeout.
      // No application retry: a failed attempt is recorded and the sequence advances.
      return pending.get(REQUEST_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
    } finally {
      pending.cancel(true);
    }
  }

  private Map<String, Object> environment() throws Exception {
    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("gitRevision", git("rev-parse", "HEAD"));
    String state = git("status", "--porcelain=v1", "--untracked-files=all");
    metadata.put("gitWorkingTreeState", state.isEmpty() ? "clean" : "dirty");
    metadata.put("gitStatus", state);
    metadata.put("os", System.getProperty("os.name"));
    metadata.put("osVersion", System.getProperty("os.version"));
    metadata.put("architecture", System.getProperty("os.arch"));
    metadata.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    metadata.put("jvm", System.getProperty("java.vm.name"));
    metadata.put("javaVersion", System.getProperty("java.runtime.version"));
    metadata.put("javaVendor", System.getProperty("java.vendor"));
    metadata.put("analyticsTransport", TRANSPORT);
    metadata.put("analyticsDrainLimitSeconds", RECORDING_DRAIN_LIMIT.toSeconds());
    metadata.put(
        "analyticsExpectedScope",
        "All valid GET redirects in warm-up and measurement, checked per saved code");
    metadata.put(
        "workload",
        Map.of(
            "savedLinks",
            LINKS,
            "clients",
            CLIENTS,
            "outstandingPerClient",
            1,
            "warmUpSeconds",
            WARM_UP.toSeconds(),
            "measurementSeconds",
            MEASUREMENT.toSeconds(),
            "connectTimeoutSeconds",
            CONNECT_TIMEOUT.toSeconds(),
            "requestTimeoutSeconds",
            REQUEST_TIMEOUT.toSeconds(),
            "followRedirects",
            false,
            "applicationRetries",
            0,
            "distribution",
            "shared round-robin across all saved links"));
    metadata.put(
        "methodology",
        "Local closed-loop baseline; app and generator share a JVM; "
            + "disposable PostgreSQL"
            + ("kafka".equals(TRANSPORT) ? " and single-broker Kafka" : "")
            + "; nearest-rank percentiles over all attempts; "
            + "completed requests/s = all completed attempts / (measurement + final drain); "
            + "startup, creation, warm-up and destination loading excluded");
    return metadata;
  }

  private String git(String... arguments) throws Exception {
    var command = new ArrayList<String>();
    command.add("git");
    command.addAll(List.of(arguments));
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
        throw new IllegalStateException("Cannot capture Git metadata");
      }
      return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    } finally {
      process.destroyForcibly();
    }
  }
}
