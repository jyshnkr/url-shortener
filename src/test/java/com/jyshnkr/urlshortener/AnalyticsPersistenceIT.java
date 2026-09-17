package com.jyshnkr.urlshortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jyshnkr.urlshortener.links.config.LinksSettings;
import com.jyshnkr.urlshortener.links.dao.LinkAnalyticsStore;
import com.jyshnkr.urlshortener.links.dao.LinkStore;
import com.jyshnkr.urlshortener.links.model.RedirectIncrement;
import com.jyshnkr.urlshortener.links.service.LinkAnalyticsService;
import com.jyshnkr.urlshortener.links.service.LinkCreationService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

class AnalyticsPersistenceIT {
  @Test
  void v3PreservesPopulatedV2MappingsAndStartsTheirStatsAtZero() {
    try (var database = new PostgreSQLContainer("postgres:17.11")) {
      database.start();
      migrate(database, "2");
      var dataSource = source(database);
      var creation =
          new LinkCreationService(
              new LinkStore(dataSource),
              new LinksSettings("https://old.example"),
              () -> "Existing01");
      var created = creation.create("https://example.invalid/legacy").link();
      var jdbc = new JdbcTemplate(dataSource);
      var before = jdbc.queryForList("SELECT * FROM short_links");
      migrate(database, null);
      assertThat(jdbc.queryForList("SELECT * FROM short_links"))
          .usingRecursiveComparison()
          .isEqualTo(before);
      assertThat(creation.create(created.destinationUrl()).link()).isEqualTo(created);
      var stats =
          new LinkAnalyticsService(new LinkAnalyticsStore(dataSource)).stats(created.code());
      assertThat(stats.redirectCount()).isZero();
      assertThat(stats.lastRedirectedAt()).isNull();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM link_analytics", Long.class)).isZero();
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "INSERT INTO link_analytics VALUES (?, 0, CURRENT_TIMESTAMP)",
                      created.code()))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThatThrownBy(
              () -> jdbc.update("INSERT INTO link_analytics VALUES (?, 1, NULL)", created.code()))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "INSERT INTO link_analytics VALUES ('Absent1234', 1, CURRENT_TIMESTAMP)"))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }

  @Test
  void concurrentBatchesAddCountsWithoutMovingTimestampsBackward() throws Exception {
    try (var database = new PostgreSQLContainer("postgres:17.11")) {
      database.start();
      migrate(database, null);
      var dataSource = source(database);
      for (String code : List.of("Stored0001", "Stored0002")) {
        new LinkCreationService(
                new LinkStore(dataSource), new LinksSettings("https://short.example"), () -> code)
            .create("https://example.invalid/" + code);
      }
      var store = new LinkAnalyticsStore(dataSource);
      Instant newest = Instant.parse("2026-09-17T20:00:00.123456Z");
      store.increment(
          List.of(
              new RedirectIncrement("Stored0001", 2, newest),
              new RedirectIncrement("Stored0002", 7, newest.minusSeconds(1))));
      var barrier = new CyclicBarrier(8);
      try (var callers = Executors.newFixedThreadPool(8)) {
        var tasks = new ArrayList<Future<?>>();
        for (int i = 0; i < 8; i++) {
          tasks.add(
              callers.submit(
                  () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    new LinkAnalyticsStore(dataSource)
                        .increment(
                            List.of(
                                new RedirectIncrement("Stored0001", 3, newest.minusSeconds(100))));
                    return null;
                  }));
        }
        for (var task : tasks) {
          task.get(10, TimeUnit.SECONDS);
        }
      }
      var service = new LinkAnalyticsService(store);
      assertThat(service.stats("Stored0001").redirectCount()).isEqualTo(26);
      assertThat(service.stats("Stored0001").lastRedirectedAt()).isEqualTo(newest);
      assertThat(service.stats("Stored0002").redirectCount()).isEqualTo(7);
      assertThat(service.stats("Stored0002").lastRedirectedAt()).isEqualTo(newest.minusSeconds(1));
    }
  }

  @Test
  void gracefulRestartKeepsRecordedCountsAndDatabaseOutageReturnsSafeStats503() throws Exception {
    var json = JsonMapper.builder().build();
    try (var database = new PostgreSQLContainer("postgres:17.11");
        var client =
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()) {
      database.start();
      String code;
      try (var application = start(database)) {
        var created =
            client.send(
                HttpRequest.newBuilder(endpoint(application, "/api/v1/links"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            "{\"destinationUrl\":\"https://example.invalid/restart-stats\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        code = json.readTree(created.body()).get("code").stringValue();
        for (int i = 0; i < 3; i++) {
          assertThat(get(client, application, "/r/" + code).statusCode()).isEqualTo(302);
        }
        // Close immediately, without polling stats: the shutdown drain must flush the partial
        // batch.
      }
      try (var application = start(database)) {
        var response = get(client, application, "/api/v1/links/" + code + "/stats");
        assertThat(response.statusCode()).isEqualTo(200);
        var stats = json.readTree(response.body());
        assertThat(stats.get("redirectCount").longValue()).isEqualTo(3);
        assertThat(Instant.parse(stats.get("lastRedirectedAt").stringValue()))
            .isBeforeOrEqualTo(Instant.now());
        database.stop();
        var failed = get(client, application, "/api/v1/links/" + code + "/stats");
        assertThat(failed.statusCode()).isEqualTo(503);
        assertThat(failed.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(failed.body())
            .contains("Retry the same request")
            .doesNotContain("jdbc:", "SELECT", "Exception");
      }
    }
  }

  private ConfigurableApplicationContext start(PostgreSQLContainer database) {
    return new SpringApplicationBuilder(UrlShortenerApplication.class)
        .run(
            "--server.port=0",
            "--spring.datasource.url=" + database.getJdbcUrl(),
            "--spring.datasource.username=" + database.getUsername(),
            "--spring.datasource.password=" + database.getPassword());
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

  private DriverManagerDataSource source(PostgreSQLContainer database) {
    return new DriverManagerDataSource(
        database.getJdbcUrl(), database.getUsername(), database.getPassword());
  }

  private void migrate(PostgreSQLContainer database, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }
}
