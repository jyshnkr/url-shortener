package com.jyshnkr.urlshortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.jyshnkr.urlshortener.links.analytics.AnalyticsBatchWriter;
import com.jyshnkr.urlshortener.links.analytics.JdbcAnalyticsWriter;
import com.jyshnkr.urlshortener.links.analytics.RecordingSettings;
import com.jyshnkr.urlshortener.links.analytics.RedirectRecorder;
import com.jyshnkr.urlshortener.links.model.RedirectIncrement;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, AnalyticsFailureIT.ControlledRecording.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnalyticsFailureIT {
  @LocalServerPort private int port;
  @Autowired private ControlledWriter writer;
  @Autowired private RedirectRecorder recorder;

  @Test
  void fullQueueAndFailedBatchLeaveRedirectsAvailableAndLaterWritesRecover() throws Exception {
    var json = JsonMapper.builder().build();
    try (var client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()) {
      var created =
          client.send(
              HttpRequest.newBuilder(uri("/api/v1/links"))
                  .timeout(Duration.ofSeconds(10))
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"destinationUrl\":\"https://example.invalid/full-queue\"}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(created.statusCode()).isEqualTo(201);
      String code = json.readTree(created.body()).get("code").stringValue();
      try {
        assertThat(get(client, "/r/" + code).statusCode()).isEqualTo(302);
        assertThat(writer.entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertTimeoutPreemptively(
            Duration.ofSeconds(1),
            () -> {
              assertThat(get(client, "/r/" + code).statusCode()).isEqualTo(302);
              var dropped = get(client, "/r/" + code);
              assertThat(dropped.statusCode()).isEqualTo(302);
              assertThat(dropped.headers().firstValue("Cache-Control")).contains("no-store");
              assertThat(dropped.body()).isEmpty();
            });
        assertThat(recorder.diagnostics().queueFullDrops()).isEqualTo(1);
      } finally {
        writer.release.countDown();
      }
      await()
          .atMost(Duration.ofSeconds(3))
          .until(() -> recorder.diagnostics().confirmedWrites() == 1);
      assertThat(recorder.diagnostics().unconfirmedWrites()).isEqualTo(1);
      assertThat(get(client, "/r/" + code).statusCode()).isEqualTo(302);
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> {
                var response = get(client, "/api/v1/links/" + code + "/stats");
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(json.readTree(response.body()).get("redirectCount").longValue())
                    .isEqualTo(2);
              });
      assertThat(recorder.diagnostics().submitted()).isEqualTo(4);
      assertThat(recorder.diagnostics().confirmedWrites()).isEqualTo(2);
    }
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private HttpResponse<String> get(HttpClient client, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ControlledRecording {
    @Bean
    ControlledWriter controlledWriter(HikariDataSource primary) {
      return new ControlledWriter(primary);
    }

    @Bean(destroyMethod = "close")
    @Primary
    RedirectRecorder controlledRecorder(ControlledWriter writer) {
      return new RedirectRecorder(
          writer,
          writer.metrics,
          Clock.systemUTC(),
          new RecordingSettings(
              1, 1, Duration.ofMillis(10), Duration.ofSeconds(1), Duration.ofSeconds(3)));
    }
  }

  static final class ControlledWriter implements AnalyticsBatchWriter {
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean first = new AtomicBoolean(true);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final JdbcAnalyticsWriter delegate;

    ControlledWriter(HikariDataSource primary) {
      delegate = new JdbcAnalyticsWriter(primary);
    }

    @Override
    public void write(List<RedirectIncrement> increments) {
      if (first.compareAndSet(true, false)) {
        entered.countDown();
        try {
          if (!release.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Test gate timed out");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        throw new IllegalStateException("Simulated unconfirmed write");
      }
      delegate.write(increments);
    }

    @Override
    public void close() {
      release.countDown();
      delegate.close();
      metrics.close();
    }
  }
}
