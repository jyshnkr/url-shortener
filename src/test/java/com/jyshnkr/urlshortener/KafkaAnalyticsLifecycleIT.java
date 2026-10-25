package com.jyshnkr.urlshortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.jyshnkr.urlshortener.links.analytics.KafkaRedirectPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

class KafkaAnalyticsLifecycleIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void eventsPublishedBeforeAGracefulShutdownAreSavedOnceAfterRestart() throws Exception {
    try (var database = new PostgreSQLContainer("postgres:17.11");
        var broker = TestcontainersConfig.kafka();
        var client = client()) {
      database.start();
      broker.start();
      String code;
      try (var application = start(database, broker.getBootstrapServers())) {
        code = createLink(client, application);
        for (int index = 0; index < 3; index++) {
          assertThat(get(client, application, "/r/" + code).statusCode()).isEqualTo(302);
        }
        // Close immediately: shutdown flushes the producer, whether or not the events are saved.
      }
      try (var application = start(database, broker.getBootstrapServers())) {
        await()
            .atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(count(client, application, code)).isEqualTo(3));
        await()
            .during(Duration.ofMillis(600))
            .atMost(Duration.ofSeconds(3))
            .untilAsserted(() -> assertThat(count(client, application, code)).isEqualTo(3));
        assertThat(
                application
                    .getBean(JdbcTemplate.class)
                    .queryForObject("SELECT count(*) FROM processed_redirect_events", Long.class))
            .isEqualTo(3);
      }
    }
  }

  @Test
  void anUnreachableBrokerLeavesRedirectsWorkingWithinTheBoundedWaitAndCountsTheLostEvents()
      throws Exception {
    try (var database = new PostgreSQLContainer("postgres:17.11");
        var client = client()) {
      database.start();
      try (var application = start(database, "127.0.0.1:" + closedPort())) {
        String code = createLink(client, application);
        var publisher = application.getBean(KafkaRedirectPublisher.class);
        for (int index = 0; index < 3; index++) {
          // Kafka's own default would block for 60 seconds; this service allows 50 ms.
          var response =
              assertTimeoutPreemptively(
                  Duration.ofSeconds(1), () -> get(client, application, "/r/" + code));
          assertThat(response.statusCode()).isEqualTo(302);
          assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
          assertThat(response.body()).isEmpty();
        }
        await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> assertThat(publisher.diagnostics().failed()).isEqualTo(3));
        assertThat(publisher.diagnostics().published()).isZero();
        assertThat(
                application
                    .getBean(MeterRegistry.class)
                    .get("shortener.analytics.events")
                    .tag("outcome", "publish_failed")
                    .functionCounter()
                    .count())
            .isEqualTo(3);
        assertThat(count(client, application, code)).isZero();
      }
    }
  }

  private static HttpClient client() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  private static int closedPort() throws IOException {
    try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private ConfigurableApplicationContext start(PostgreSQLContainer database, String kafka) {
    return new SpringApplicationBuilder(UrlShortenerApplication.class)
        .run(
            "--server.port=0",
            "--spring.datasource.url=" + database.getJdbcUrl(),
            "--spring.datasource.username=" + database.getUsername(),
            "--spring.datasource.password=" + database.getPassword(),
            "--spring.kafka.bootstrap-servers=" + kafka,
            "--spring.main.banner-mode=off");
  }

  private String createLink(HttpClient client, ConfigurableApplicationContext application)
      throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(endpoint(application, "/api/v1/links"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        JSON.createObjectNode()
                            .put(
                                "destinationUrl",
                                "https://example.invalid/kafka-lifecycle/" + UUID.randomUUID())
                            .toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(201);
    return JSON.readTree(response.body()).get("code").stringValue();
  }

  private long count(HttpClient client, ConfigurableApplicationContext application, String code)
      throws Exception {
    var response = get(client, application, "/api/v1/links/" + code + "/stats");
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body()).get("redirectCount").longValue();
  }

  private HttpResponse<String> get(
      HttpClient client, ConfigurableApplicationContext application, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(endpoint(application, path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private URI endpoint(ConfigurableApplicationContext application, String path) {
    return URI.create(
        "http://127.0.0.1:"
            + ((WebServerApplicationContext) application).getWebServer().getPort()
            + path);
  }
}
