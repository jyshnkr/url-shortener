package com.jyshnkr.urlshortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jyshnkr.urlshortener.links.config.LinksSettings;
import com.jyshnkr.urlshortener.links.dao.LinkStore;
import com.jyshnkr.urlshortener.links.service.LinkCreationService;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class DestinationMigrationIT {

  @Test
  void populatedV1MigrationPreservesEveryOriginalFieldAndBackfillsUtf8Fingerprints() {
    try (var database = new PostgreSQLContainer("postgres:17.11")) {
      database.start();
      migrate(database, "1");
      var jdbc = jdbc(database);
      insertLegacy(jdbc, "LegacyCode", "https://example.com/é/🚀");
      var before = jdbc.queryForMap("SELECT * FROM short_links");

      migrate(database, null);

      var after = jdbc.queryForMap("SELECT * FROM short_links");
      assertThat(after).containsAllEntriesOf(before);
      assertThat((byte[]) after.get("destination_hash"))
          .isEqualTo(
              java.util.HexFormat.of()
                  .parseHex("7890a941ef9431d8b27559905f7d149488dfe272c9b222723f46938e1b1f6149"));
      assertThat(jdbc.queryForObject("SELECT count(*) FROM short_links", Integer.class))
          .isEqualTo(1);
      var service =
          new LinkCreationService(
              new LinkStore(jdbc.getDataSource()),
              new LinksSettings("https://changed.example"),
              () -> {
                throw new AssertionError("Must reuse");
              });
      var reused = service.create("https://example.com/é/🚀");
      assertThat(reused.created()).isFalse();
      assertThat(reused.link().code()).isEqualTo("LegacyCode");
      assertThat(reused.link().shortUrl()).isEqualTo("https://legacy.example/r/LegacyCode");
    }
  }

  @Test
  void duplicateDestinationsAbortAndRollBackTheEntireMigration() {
    try (var database = new PostgreSQLContainer("postgres:17.11")) {
      database.start();
      migrate(database, "1");
      var jdbc = jdbc(database);
      insertLegacy(jdbc, "Duplicate1", "https://example.com/same");
      insertLegacy(jdbc, "Duplicate2", "https://example.com/same");
      var before = jdbc.queryForList("SELECT * FROM short_links ORDER BY short_code");

      assertThatThrownBy(() -> migrate(database, null)).isInstanceOf(RuntimeException.class);

      assertThat(jdbc.queryForList("SELECT * FROM short_links ORDER BY short_code"))
          .isEqualTo(before);
      assertThat(
              jdbc.queryForObject(
                  """
          SELECT count(*) FROM information_schema.columns
          WHERE table_name = 'short_links' AND column_name = 'destination_hash'
          """,
                  Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  """
          SELECT column_default FROM information_schema.columns
          WHERE table_name = 'short_links' AND column_name = 'creation_request_id'
          """,
                  String.class))
          .isNull();
      assertThat(
              jdbc.queryForList(
                  "SELECT version FROM flyway_schema_history WHERE success", String.class))
          .containsExactly("1");
    }
  }

  @Test
  void aLockedV1TableAbortsMigrationWithinABoundedWait() throws Exception {
    try (var database = new PostgreSQLContainer("postgres:17.11")) {
      database.start();
      migrate(database, "1");
      try (var lock =
              DriverManager.getConnection(
                  database.getJdbcUrl(), database.getUsername(), database.getPassword());
          var callers = Executors.newSingleThreadExecutor()) {
        lock.setAutoCommit(false);
        try (var statement = lock.createStatement()) {
          statement.execute("LOCK TABLE short_links IN ACCESS SHARE MODE");
        }
        try {
          var migration = callers.submit(() -> migrate(database, null));
          assertThatThrownBy(() -> migration.get(10, TimeUnit.SECONDS))
              .isInstanceOf(ExecutionException.class)
              .rootCause()
              .isInstanceOfSatisfying(
                  SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("55P03"));
        } finally {
          lock.rollback();
        }
      }
      assertThat(
              jdbc(database)
                  .queryForList(
                      "SELECT version FROM flyway_schema_history WHERE success", String.class))
          .containsExactly("1");
      migrate(database, null);
    }
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

  private JdbcTemplate jdbc(PostgreSQLContainer database) {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            database.getJdbcUrl(), database.getUsername(), database.getPassword()));
  }

  private void insertLegacy(JdbcTemplate jdbc, String code, String destination) {
    jdbc.update(
        "INSERT INTO short_links VALUES (?, ?, ?, ?, ?)",
        code,
        destination,
        UUID.randomUUID(),
        "https://legacy.example/r/" + code,
        OffsetDateTime.parse("2026-09-16T18:00:00Z"));
  }
}
