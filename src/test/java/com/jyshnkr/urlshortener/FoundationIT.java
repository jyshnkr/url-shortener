package com.jyshnkr.urlshortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class FoundationIT {

  private static final UUID REQUEST_ID = UUID.fromString("fd604efa-9182-486e-818a-e9c00c1d6287");
  private static final UUID OTHER_REQUEST_ID =
      UUID.fromString("bd5bb23b-a218-410d-aa14-b8f93193e03b");
  private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-16T18:00:00Z");

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void applicationStartsAndReportsHealthyWithIsolatedPostgres() throws Exception {
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();

      var response = client.send(request, HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(200);
      JSONAssert.assertEquals("{\"status\":\"UP\"}", response.body(), false);
    }
  }

  @Test
  @Transactional
  void migratedSchemaStoresTheCompleteCreationResult() {
    insertLink("Ab3dE6gH9J", REQUEST_ID);

    var result =
        jdbc.queryForObject(
            """
            SELECT short_code, destination_url, creation_request_id, short_url, created_at
            FROM short_links WHERE short_code = ?
            """,
            (row, rowNumber) ->
                List.of(
                    row.getString("short_code"),
                    row.getString("destination_url"),
                    row.getObject("creation_request_id", UUID.class),
                    row.getString("short_url"),
                    row.getObject("created_at", OffsetDateTime.class).toInstant()),
            "Ab3dE6gH9J");

    assertThat(result)
        .containsExactly(
            "Ab3dE6gH9J",
            "https://example.com/Ab3dE6gH9J",
            REQUEST_ID,
            "http://localhost:8080/r/Ab3dE6gH9J",
            CREATED_AT.toInstant());
  }

  @Test
  @Transactional
  void duplicateShortCodesAreRejected() {
    insertLink("Ab3dE6gH9J", REQUEST_ID);

    assertThatThrownBy(() -> insertLink("Ab3dE6gH9J", OTHER_REQUEST_ID))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  @Transactional
  void duplicateCreationRequestIdsAreRejected() {
    insertLink("Ab3dE6gH9J", REQUEST_ID);

    assertThatThrownBy(() -> insertLink("Zy9xW6vU3T", REQUEST_ID))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "short_code",
        "destination_url",
        "creation_request_id",
        "short_url",
        "created_at",
        "destination_hash"
      })
  @Transactional
  void allCreationResultFieldsAreRequired(String column) {
    insertLink("Ab3dE6gH9J", REQUEST_ID);

    // The identifier comes only from the fixed test cases above, never from user input.
    assertThatThrownBy(() -> jdbc.update("UPDATE short_links SET " + column + " = NULL"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23502"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "Ab3dE6gH9",
        "Ab3dE6gH9JK",
        "Ab3dE6gH9_",
        "Ab3dE6gH9/",
        "Ab3dE6gH9é",
        "Ab3dE6gH9J\n",
        " Ab3dE6gH9"
      })
  @Transactional
  void shortCodesMustBeExactlyTenAsciiLettersOrDigits(String code) {
    assertThatThrownBy(() -> insertLink(code, REQUEST_ID))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"));
  }

  @Test
  @Transactional
  void duplicateDestinationsAreRejected() {
    insertLink("Ab3dE6gH9J", REQUEST_ID);
    assertThatThrownBy(
            () -> insertLink("Zy9xW6vU3T", OTHER_REQUEST_ID, "https://example.com/Ab3dE6gH9J"))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "UPDATE short_links SET destination_hash = decode('00', 'hex')",
        "UPDATE short_links SET destination_url = 'https://changed.example'"
      })
  @Transactional
  void fingerprintsMustMatchTheStoredDestination(String sql) {
    insertLink("Ab3dE6gH9J", REQUEST_ID);
    assertThatThrownBy(() -> jdbc.update(sql))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"));
  }

  @Test
  @Transactional
  void newRowsReceiveDatabaseGeneratedInternalIds() {
    jdbc.update(
        """
        INSERT INTO short_links (short_code, destination_url, destination_hash, short_url, created_at)
        VALUES ('DefaultId1', 'https://example.com/default',
          sha256(convert_to('https://example.com/default', 'UTF8')),
          'https://short.example/r/DefaultId1', CURRENT_TIMESTAMP)
        """);
    var identity =
        jdbc.queryForObject(
            "SELECT creation_request_id FROM short_links WHERE short_code = 'DefaultId1'",
            UUID.class);
    assertThat(identity).isNotNull();
    assertThat(identity.version()).isEqualTo(4);
  }

  @Test
  @Transactional
  void shortCodesAreCaseSensitive() {
    insertLink("Ab3dE6gH9J", REQUEST_ID);
    insertLink("ab3dE6gH9J", OTHER_REQUEST_ID);

    assertThat(
            jdbc.queryForList(
                "SELECT creation_request_id FROM short_links WHERE short_code = ?",
                UUID.class,
                "Ab3dE6gH9J"))
        .containsExactly(REQUEST_ID);
    assertThat(
            jdbc.queryForList(
                "SELECT creation_request_id FROM short_links WHERE short_code = ?",
                UUID.class,
                "ab3dE6gH9J"))
        .containsExactly(OTHER_REQUEST_ID);
  }

  private void insertLink(String code, UUID requestId) {
    insertLink(code, requestId, "https://example.com/" + code);
  }

  private void insertLink(String code, UUID requestId, String destination) {
    jdbc.update(
        """
        INSERT INTO short_links
          (short_code, destination_url, destination_hash, creation_request_id, short_url, created_at)
        VALUES (?, ?, sha256(convert_to(?, 'UTF8')), ?, ?, ?)
        """,
        code,
        destination,
        destination,
        requestId,
        "http://localhost:8080/r/" + code,
        CREATED_AT);
  }
}
