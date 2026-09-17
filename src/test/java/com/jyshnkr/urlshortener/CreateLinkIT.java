package com.jyshnkr.urlshortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "shortener.base-url=https://short.example")
@Import(TestcontainersConfig.class)
class CreateLinkIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort private int port;

  @ParameterizedTest
  @MethodSource("validDestinations")
  void validDestinationsArePreservedWithoutFetchingThem(String destination) throws Exception {
    var payload = JSON.createObjectNode().put("destinationUrl", destination).toString();
    var response = create(null, payload);

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(JSON.readTree(response.body()).get("destinationUrl").stringValue())
        .isEqualTo(destination);
  }

  private static Stream<String> validDestinations() {
    return Stream.of(
        "HTTP://EXAMPLE.COM/path?b=2&a=1#Section",
        "https://example.invalid/a%20b",
        "http://localhost:9090/test",
        "http://127.0.0.1/test",
        "http://[::1]:8080/test",
        "https://xn--bcher-kva.example/path",
        "https://example.com/\uD83D\uDE80",
        "https://example.com/" + "a".repeat(2028));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not-a-uuid", "061b32f3-6b9c-4c30-a463-3a1889479a11"})
  void obsoleteHeadersReturn400WithRemovalInstructions(String value) throws Exception {
    var response = create(value, "{\"destinationUrl\":\"https://example.com\"}");
    assertThat(response.statusCode()).isEqualTo(400);
    var error = JSON.readTree(response.body());
    assertThat(error.get("status").intValue()).isEqualTo(400);
    assertThat(error.get("title").stringValue()).isEqualTo("Bad Request");
    assertThat(error.get("detail").stringValue()).contains("Remove", "Idempotency-Key");
    assertThat(response.body())
        .doesNotContain(value.isEmpty() ? "Exception" : value, "SELECT", "INSERT");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "[]",
        "{}",
        "null",
        "{",
        "{\"destinationUrl\":null}",
        "{\"destinationUrl\":42}",
        "{\"destinationUrl\":[]}",
        "{\"destinationUrl\":\"https://example.com/\\uD800\"}",
        "{\"destinationUrl\":\"https://example.com/\\uDC00\"}",
        "{\"destinationUrl\":\"https://example.com\"} true",
        "{\"destinationUrl\":\"https://example.com\",\"expiresAt\":\"2030-01-01T00:00:00Z\"}"
      })
  void invalidRequestBodiesHaveASafeConsistent400Response(String payload) throws Exception {
    var response = create(null, payload);

    assertThat(response.statusCode()).isEqualTo(400);
    var error = JSON.readTree(response.body());
    assertThat(error.get("status").intValue()).isEqualTo(400);
    assertThat(error.get("title").stringValue()).isEqualTo("Bad Request");
    assertThat(error.get("detail").stringValue()).isNotBlank();
    assertThat(response.body())
        .doesNotContain("https://example.com", "Exception", "SELECT", "INSERT");
  }

  @Test
  void validCreationReturnsAShortLinkFromTheConfiguredBase() throws Exception {
    var response = create(null, "{\"destinationUrl\":\"https://example.com/docs\"}");

    assertThat(response.statusCode()).isEqualTo(201);
    var body = JSON.readTree(response.body());
    assertThat(body.size()).isEqualTo(3);
    assertThat(body.get("code").stringValue()).matches("[A-Za-z0-9]{10}");
    assertThat(body.get("destinationUrl").stringValue()).isEqualTo("https://example.com/docs");
    assertThat(body.get("shortUrl").stringValue())
        .isEqualTo("https://short.example/r/" + body.get("code").stringValue());
    assertThat(response.headers().firstValue("Location"))
        .contains(body.get("shortUrl").stringValue());
  }

  @Test
  void matchingRetryReturnsTheOriginalCreationResult() throws Exception {
    String payload = "{\"destinationUrl\":\"https://example.com/retry\"}";
    var original = create(null, payload);
    var replay = create(null, payload);

    assertThat(original.statusCode()).isEqualTo(201);
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(replay.body()).isEqualTo(original.body());
    assertThat(replay.headers().firstValue("Location"))
        .isEqualTo(original.headers().firstValue("Location"));
  }

  @Test
  void differentExactDestinationStringsRemainIndependent() throws Exception {
    var first = create(null, "{\"destinationUrl\":\"https://example.com/exact%41\"}");
    var second = create(null, "{\"destinationUrl\":\"https://example.com/exactA\"}");
    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(second.statusCode()).isEqualTo(201);
    assertThat(JSON.readTree(first.body()).get("code"))
        .isNotEqualTo(JSON.readTree(second.body()).get("code"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"idempotency-key", "IDEMPOTENCY-KEY", "iDeMpOtEnCy-KeY"})
  void obsoleteHeaderNamesAreRejectedRegardlessOfCase(String name) throws Exception {
    var response = create(name, "", "{\"destinationUrl\":\"https://example.com/old-header\"}");
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(response.body()).get("detail").stringValue())
        .contains("Remove", "Idempotency-Key");
  }

  @Test
  void aLongUnicodeDestinationCanBeCreatedAndReused() throws Exception {
    // Varied three-byte characters exceed a B-tree text index entry without being highly
    // compressible.
    var destination = new StringBuilder("https://example.com/");
    for (int index = 0; index < 2000; index++) {
      destination.append((char) (0x4e00 + index));
    }
    String payload =
        JSON.createObjectNode().put("destinationUrl", destination.toString()).toString();
    var created = create(null, payload);
    var reused = create(null, payload);
    assertThat(created.statusCode()).isEqualTo(201);
    assertThat(reused.statusCode()).isEqualTo(200);
    assertThat(reused.body()).isEqualTo(created.body());
    assertThat(JSON.readTree(created.body()).get("destinationUrl").stringValue())
        .isEqualTo(destination.toString());
  }

  @Test
  void concurrentIdenticalHttpRequestsReturnOneCreationAndReuseForAllOthers() throws Exception {
    String payload = "{\"destinationUrl\":\"https://example.com/http-race\"}";
    try (var callers = Executors.newFixedThreadPool(8)) {
      var pending = new ArrayList<Future<HttpResponse<String>>>();
      for (int index = 0; index < 8; index++) {
        pending.add(callers.submit(() -> create(null, payload)));
      }
      var responses = new ArrayList<HttpResponse<String>>();
      for (var result : pending) {
        responses.add(result.get(15, TimeUnit.SECONDS));
      }
      assertThat(responses.stream().filter(response -> response.statusCode() == 201).count())
          .isEqualTo(1);
      assertThat(responses.stream().filter(response -> response.statusCode() == 200).count())
          .isEqualTo(7);
      assertThat(responses)
          .allSatisfy(
              response -> {
                assertThat(response.body()).isEqualTo(responses.getFirst().body());
                assertThat(response.headers().firstValue("Location"))
                    .isEqualTo(responses.getFirst().headers().firstValue("Location"));
              });
    }
  }

  private HttpResponse<String> create(String headerValue, String body) throws Exception {
    return create("Idempotency-Key", headerValue, body);
  }

  private HttpResponse<String> create(String headerName, String headerValue, String body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/links"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Forwarded", "host=attacker.invalid;proto=http")
            .header("X-Forwarded-Host", "attacker.invalid")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (headerValue != null) {
      request.header(headerName, headerValue);
    }
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }
}
