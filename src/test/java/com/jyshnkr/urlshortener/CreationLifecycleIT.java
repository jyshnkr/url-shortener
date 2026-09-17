package com.jyshnkr.urlshortener;

import static org.assertj.core.api.Assertions.assertThat;

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

class CreationLifecycleIT {

  @Test
  void aSilentDatabaseNetworkStallReturns503WithinTheClientDeadline() throws Exception {
    try (var database = new PostgreSQLContainer("postgres:17.11")) {
      database.start();
      try (var proxy = new StallingDatabaseProxy(database.getHost(), database.getMappedPort(5432));
          var application =
              start(
                  database,
                  "https://short.example",
                  "jdbc:postgresql://127.0.0.1:"
                      + proxy.port()
                      + "/"
                      + database.getDatabaseName())) {
        var original = create(application, UUID.randomUUID().toString());
        assertThat(original.statusCode()).isEqualTo(201);
        String code =
            JsonMapper.builder().build().readTree(original.body()).get("code").stringValue();
        proxy.stall();
        try {
          assertRedirectUnavailable(follow(application, code, "GET"));
          var response = create(application, UUID.randomUUID().toString());
          assertThat(response.statusCode()).isEqualTo(503);
          assertThat(response.body()).doesNotContain("jdbc:", "Exception", "SELECT");
        } finally {
          // Release stalled sockets even if the timeout assertion fails, before app shutdown.
          proxy.close();
        }
      }
    }
  }

  @Test
  void reuseSurvivesAnApplicationRestartAndABaseUrlChange() throws Exception {
    try (var database = new PostgreSQLContainer("postgres:17.11")) {
      database.start();
      String identity = UUID.randomUUID().toString();
      HttpResponse<String> original;
      try (var application = start(database, "https://first.example")) {
        original = create(application, identity);
        assertThat(original.statusCode()).isEqualTo(201);
        assertSavedRedirect(application, original);
      }

      try (var restarted = start(database, "https://changed.example/")) {
        assertSavedRedirect(restarted, original);
        var replay = create(restarted, identity);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(original.body());
        assertThat(replay.headers().firstValue("Location"))
            .isEqualTo(original.headers().firstValue("Location"));

        var independent = create(restarted, UUID.randomUUID().toString());
        assertThat(independent.statusCode()).isEqualTo(201);
        assertThat(independent.headers().firstValue("Location").orElseThrow())
            .startsWith("https://changed.example/r/");
        assertThat(independent.body()).isNotEqualTo(original.body());
      }
    }
  }

  @Test
  void anActualDatabaseOutageReturns503WithoutExposingInternalDetails() throws Exception {
    try (var database = new PostgreSQLContainer("postgres:17.11")) {
      database.start();
      try (var application = start(database, "https://short.example")) {
        var original = create(application, UUID.randomUUID().toString());
        assertThat(original.statusCode()).isEqualTo(201);
        String code =
            JsonMapper.builder().build().readTree(original.body()).get("code").stringValue();
        // This container belongs only to this test, never the local development database.
        database.stop();

        assertThat(follow(application, "malformed", "GET").statusCode()).isEqualTo(404);
        assertRedirectUnavailable(follow(application, code, "GET"));
        var head = follow(application, code, "HEAD");
        assertThat(head.statusCode()).isEqualTo(503);
        assertThat(head.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(head.headers().firstValue("Location")).isEmpty();
        assertThat(head.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(head.body()).isEmpty();

        var response = create(application, UUID.randomUUID().toString());

        assertThat(response.statusCode()).isEqualTo(503);
        var error = JsonMapper.builder().build().readTree(response.body());
        assertThat(error.get("status").intValue()).isEqualTo(503);
        assertThat(error.get("title").stringValue()).isEqualTo("Service Unavailable");
        assertThat(error.get("detail").stringValue()).contains("Retry", "same request");
        assertThat(response.body()).doesNotContain("jdbc:", "postgres", "SELECT", "Exception");
      }
    }
  }

  @Test
  void aFingerprintCandidateWithDifferentTextReturnsSafe503InsteadOfTheWrongLink()
      throws Exception {
    try (var database = new PostgreSQLContainer("postgres:17.11")) {
      database.start();
      try (var application = start(database, "https://short.example")) {
        var original = create(application, "original");
        assertThat(original.statusCode()).isEqualTo(201);
        var jdbc = application.getBean(JdbcTemplate.class);
        // Simulate an otherwise impractical SHA-256 collision only in this disposable database.
        jdbc.execute(
            "ALTER TABLE short_links DROP CONSTRAINT short_links_destination_hash_matches");
        jdbc.update(
            "UPDATE short_links SET destination_hash = sha256(convert_to(?, 'UTF8'))",
            "https://example.com/persisted/collision");

        var response = create(application, "collision");

        assertThat(response.statusCode()).isEqualTo(503);
        var error = JsonMapper.builder().build().readTree(response.body());
        assertThat(error.get("status").intValue()).isEqualTo(503);
        assertThat(error.get("detail").stringValue()).contains("Retry", "same request");
        assertThat(response.body())
            .doesNotContain("original", "short.example", "SELECT", "Exception");
        assertThat(jdbc.queryForObject("SELECT destination_url FROM short_links", String.class))
            .isEqualTo("https://example.com/persisted/original");
      }
    }
  }

  private void assertSavedRedirect(
      ConfigurableApplicationContext application, HttpResponse<String> created) throws Exception {
    var link = JsonMapper.builder().build().readTree(created.body());
    var response = follow(application, link.get("code").stringValue(), "GET");
    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location"))
        .contains(link.get("destinationUrl").stringValue());
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(response.body()).isEmpty();
  }

  private void assertRedirectUnavailable(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(response.headers().firstValue("Location")).isEmpty();
    var error = JsonMapper.builder().build().readTree(response.body());
    assertThat(error.get("status").intValue()).isEqualTo(503);
    assertThat(error.get("title").stringValue()).isEqualTo("Service Unavailable");
    assertThat(error.get("detail").stringValue()).contains("Retry", "same request");
    assertThat(response.body())
        .doesNotContain("jdbc:", "postgres", "SELECT", "Exception", "destinationUrl");
  }

  private HttpResponse<String> follow(
      ConfigurableApplicationContext application, String code, String method) throws Exception {
    int port = ((WebServerApplicationContext) application).getWebServer().getPort();
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/r/" + code))
            .timeout(Duration.ofSeconds(10))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build();
    try (var client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(3))
            .build()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }

  private ConfigurableApplicationContext start(PostgreSQLContainer database, String baseUrl) {
    return start(database, baseUrl, database.getJdbcUrl());
  }

  private ConfigurableApplicationContext start(
      PostgreSQLContainer database, String baseUrl, String jdbcUrl) {
    return new SpringApplicationBuilder(UrlShortenerApplication.class)
        .run(
            "--server.port=0",
            "--spring.datasource.url=" + jdbcUrl,
            "--spring.datasource.username=" + database.getUsername(),
            "--spring.datasource.password=" + database.getPassword(),
            "--shortener.base-url=" + baseUrl,
            "--spring.main.banner-mode=off");
  }

  private HttpResponse<String> create(ConfigurableApplicationContext application, String identity)
      throws Exception {
    int port = ((WebServerApplicationContext) application).getWebServer().getPort();
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/links"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"destinationUrl\":\"https://example.com/persisted/" + identity + "\"}"))
            .build();
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }
}
