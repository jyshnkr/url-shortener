package com.jyshnkr.urlshortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.jyshnkr.urlshortener.links.analytics.RedirectRecorder;
import com.jyshnkr.urlshortener.links.config.LinksSettings;
import com.jyshnkr.urlshortener.links.dao.LinkStore;
import com.jyshnkr.urlshortener.links.service.LinkCreationService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class LinkAnalyticsIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  @LocalServerPort private int port;
  @Autowired private DataSource database;
  @Autowired private RedirectRecorder recorder;
  @Autowired private LinkStore links;

  @AfterEach
  void closeClient() {
    client.close();
  }

  @Test
  void aSavedLinkStartsWithZeroRecordedRedirectsAndNoTimestamp() throws Exception {
    var link = create("https://example.invalid/analytics/" + UUID.randomUUID());
    var response = send("/api/v1/links/" + link.get("code").stringValue() + "/stats", "GET");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    var stats = JSON.readTree(response.body());
    assertThat(stats.get("code").stringValue()).isEqualTo(link.get("code").stringValue());
    assertThat(stats.get("redirectCount").longValue()).isZero();
    assertThat(stats.get("lastRedirectedAt").isNull()).isTrue();
  }

  @Test
  void onlyGetRedirectsCountAndDestinationReuseKeepsTheRecordedStats() throws Exception {
    String destination = "https://example.invalid/analytics/" + UUID.randomUUID();
    var link = create(destination);
    String code = link.get("code").stringValue();
    Instant before = Instant.now().minusMillis(1);
    assertThat(send("/r/" + code, "HEAD").statusCode()).isEqualTo(302);
    assertThat(send("/r/" + code, "GET").statusCode()).isEqualTo(302);
    assertThat(send("/r/" + code, "GET").statusCode()).isEqualTo(302);
    assertThat(create(destination)).isEqualTo(link);
    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(
            () -> {
              var stats = JSON.readTree(send("/api/v1/links/" + code + "/stats", "GET").body());
              assertThat(stats.get("redirectCount").longValue()).isEqualTo(2);
              assertThat(stats.get("lastRedirectedAt").stringValue()).endsWith("Z");
              assertThat(Instant.parse(stats.get("lastRedirectedAt").stringValue()))
                  .isAfterOrEqualTo(before);
            });
    await()
        .during(Duration.ofMillis(600))
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(
            () -> {
              assertThat(send("/api/v1/links/" + code + "/stats", "HEAD").body()).isEmpty();
              var stats = JSON.readTree(send("/api/v1/links/" + code + "/stats", "GET").body());
              assertThat(stats.get("redirectCount").longValue()).isEqualTo(2);
            });
  }

  private JsonNode create(String destination) throws Exception {
    var request =
        HttpRequest.newBuilder(endpoint("/api/v1/links"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    JSON.createObjectNode().put("destinationUrl", destination).toString()))
            .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isIn(200, 201);
    return JSON.readTree(response.body());
  }

  @ParameterizedTest
  @ValueSource(strings = {"Absent1234", "short", "Bad-code01", "Unicode%C3%A9"})
  void invalidOrUnknownCodesReturnSafeUncacheable404WithoutRecording(String code) throws Exception {
    long before = recorder.diagnostics().submitted();
    for (String path : new String[] {"/r/" + code, "/api/v1/links/" + code + "/stats"}) {
      var response = send(path, "GET");
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
      assertThat(response.headers().firstValue("Location")).isEmpty();
      assertThat(JSON.readTree(response.body()).get("detail").stringValue())
          .isEqualTo("Short link not found.");
      assertThat(response.body()).doesNotContain("SELECT", "Exception", "jdbc:");
    }
    assertThat(recorder.diagnostics().submitted()).isEqualTo(before);
  }

  @Test
  void simultaneousRedirectsAreAllCounted() throws Exception {
    String code =
        create("https://example.invalid/analytics/" + UUID.randomUUID()).get("code").stringValue();
    try (var callers = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Future<?>>();
      for (int caller = 0; caller < 8; caller++) {
        tasks.add(
            callers.submit(
                () -> {
                  for (int index = 0; index < 10; index++) {
                    assertThat(send("/r/" + code, "GET").statusCode()).isEqualTo(302);
                  }
                  return null;
                }));
      }
      for (var task : tasks) {
        task.get(10, TimeUnit.SECONDS);
      }
    }
    awaitCount(code, 80);
  }

  @Test
  void statsKeepCaseSensitiveCodesIndependent() throws Exception {
    for (String code : new String[] {"CaseStat01", "casestat01"}) {
      new LinkCreationService(links, new LinksSettings("https://short.example"), () -> code)
          .create("https://example.invalid/analytics/" + code);
    }
    assertThat(send("/r/CaseStat01", "GET").statusCode()).isEqualTo(302);
    assertThat(send("/r/CaseStat01", "GET").statusCode()).isEqualTo(302);
    assertThat(send("/r/casestat01", "GET").statusCode()).isEqualTo(302);
    awaitCount("CaseStat01", 2);
    awaitCount("casestat01", 1);
    assertThat(send("/api/v1/links/CASESTAT01/stats", "GET").statusCode()).isEqualTo(404);
  }

  @Test
  void anAnalyticsRowLockDoesNotBlockRedirectsOrStatsReadsAndTheWriterRecovers() throws Exception {
    String code =
        create("https://example.invalid/analytics/" + UUID.randomUUID()).get("code").stringValue();
    String other =
        create("https://example.invalid/analytics/" + UUID.randomUUID()).get("code").stringValue();
    assertThat(send("/r/" + code, "GET").statusCode()).isEqualTo(302);
    awaitCount(code, 1);
    long failuresBefore = recorder.diagnostics().unconfirmedWrites();
    var jdbc = new JdbcTemplate(database);
    try (var lock = database.getConnection()) {
      lock.setAutoCommit(false);
      try (var statement =
          lock.prepareStatement("SELECT * FROM link_analytics WHERE short_code = ? FOR UPDATE")) {
        statement.setString(1, code);
        statement.executeQuery().close();
      }
      try {
        assertThat(send("/r/" + code, "GET").statusCode()).isEqualTo(302);
        await()
            .atMost(Duration.ofSeconds(3))
            .until(
                () ->
                    jdbc.queryForObject(
                            """
            SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'
            AND query LIKE 'INSERT INTO link_analytics%'
            """,
                            Integer.class)
                        > 0);
        assertTimeoutPreemptively(
            Duration.ofSeconds(1),
            () -> {
              assertThat(send("/r/" + other, "GET").statusCode()).isEqualTo(302);
              assertThat(stats(code).get("redirectCount").longValue()).isEqualTo(1);
            });
        await()
            .atMost(Duration.ofSeconds(5))
            .until(() -> recorder.diagnostics().unconfirmedWrites() > failuresBefore);
      } finally {
        lock.rollback();
      }
    }
    assertThat(send("/r/" + code, "GET").statusCode()).isEqualTo(302);
    awaitCount(code, 2);
    awaitCount(other, 1);
  }

  @Test
  void statsStorageFailureReturnsSafe503WhileLinkLookupStillRedirects() throws Exception {
    String code =
        create("https://example.invalid/analytics/" + UUID.randomUUID()).get("code").stringValue();
    var jdbc = new JdbcTemplate(database);
    // Fault only this disposable database's analytics table; always restore it.
    jdbc.execute("ALTER TABLE link_analytics RENAME TO paused_link_analytics");
    try {
      var response = send("/api/v1/links/" + code + "/stats", "GET");
      assertThat(response.statusCode()).isEqualTo(503);
      assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
      assertThat(response.body())
          .contains("Retry the same request")
          .doesNotContain("SELECT", "Exception", "jdbc:");
      assertThat(send("/r/" + code, "GET").statusCode()).isEqualTo(302);
    } finally {
      jdbc.execute("ALTER TABLE paused_link_analytics RENAME TO link_analytics");
    }
  }

  private JsonNode stats(String code) throws Exception {
    var response = send("/api/v1/links/" + code + "/stats", "GET");
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private void awaitCount(String code, long count) {
    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(
            () -> assertThat(stats(code).get("redirectCount").longValue()).isEqualTo(count));
  }

  private HttpResponse<String> send(String path, String method) throws Exception {
    return client.send(
        HttpRequest.newBuilder(endpoint(path))
            .timeout(Duration.ofSeconds(10))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private URI endpoint(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }
}
