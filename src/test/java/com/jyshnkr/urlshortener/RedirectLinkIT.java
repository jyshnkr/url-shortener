package com.jyshnkr.urlshortener;

import static org.assertj.core.api.Assertions.assertThat;

import com.jyshnkr.urlshortener.links.config.LinksSettings;
import com.jyshnkr.urlshortener.links.dao.LinkStore;
import com.jyshnkr.urlshortener.links.service.LinkCreationService;
import com.jyshnkr.urlshortener.links.service.LinkResolutionService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "shortener.base-url=https://short.example")
@Import(TestcontainersConfig.class)
class RedirectLinkIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort private int port;
  @Autowired private LinkStore store;
  @Autowired private LinkResolutionService resolution;

  @Test
  void codesAreCaseSensitiveAtTheServiceAndHttpBoundaries() throws Exception {
    var upper =
        new LinkCreationService(
                store, new LinksSettings("https://short.example"), () -> "CaseCode01")
            .create("https://example.invalid/upper")
            .link();
    var lower =
        new LinkCreationService(
                store, new LinksSettings("https://short.example"), () -> "caseCode01")
            .create("https://example.invalid/lower")
            .link();

    for (var link : List.of(upper, lower)) {
      assertThat(resolution.resolve(link.code())).isEqualTo(link);
      var response = follow(link.code(), "GET");
      assertThat(response.statusCode()).isEqualTo(302);
      assertThat(response.headers().firstValue("Location")).contains(link.destinationUrl());
    }
    assertThat(follow("CASECODE01", "GET").statusCode()).isEqualTo(404);
  }

  @Test
  void repeatedAndConcurrentRedirectsKeepTheSameMapping() throws Exception {
    String destination = "https://example.invalid/concurrent-redirect";
    var created = create(destination);
    assertThat(created.statusCode()).isEqualTo(201);
    String code = JSON.readTree(created.body()).get("code").stringValue();
    var barrier = new CyclicBarrier(8);
    try (var callers = Executors.newFixedThreadPool(8)) {
      var pending = new ArrayList<Future<HttpResponse<String>>>();
      for (int index = 0; index < 8; index++) {
        pending.add(
            callers.submit(
                () -> {
                  barrier.await(5, TimeUnit.SECONDS);
                  return follow(code, "GET");
                }));
      }
      for (var result : pending) {
        var response = result.get(15, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).contains(destination);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).isEmpty();
      }
    }
    for (int index = 0; index < 3; index++) {
      assertThat(follow(code, "GET").headers().firstValue("Location")).contains(destination);
    }
    assertThat(create(destination).body()).isEqualTo(created.body());
  }

  @Test
  void automaticHeadMatchesGetStatusAndHeadersWithoutABody() throws Exception {
    var created = create("https://example.invalid/head/e\u0301?q=%2f#fragment");
    assertThat(created.statusCode()).isEqualTo(201);
    String code = JSON.readTree(created.body()).get("code").stringValue();
    for (String candidate : List.of(code, "Absent1234", "bad-code")) {
      var get = follow(candidate, "GET");
      var head = follow(candidate, "HEAD");
      assertThat(head.statusCode()).isEqualTo(get.statusCode());
      for (String header : List.of("Location", "Cache-Control", "Content-Type")) {
        assertThat(head.headers().allValues(header)).isEqualTo(get.headers().allValues(header));
      }
      assertThat(head.body()).isEmpty();
    }
  }

  @ParameterizedTest
  @MethodSource("destinationsAndLocations")
  void locationsEncodeOnlyNonAsciiWithoutChangingSavedDestinations(
      String destination, String location) throws Exception {
    var created = create(destination);
    assertThat(created.statusCode()).isEqualTo(201);
    var link = JSON.readTree(created.body());
    assertThat(link.get("destinationUrl").stringValue()).isEqualTo(destination);

    var response = follow(link.get("code").stringValue(), "GET");

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location")).contains(location);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(response.body()).isEmpty();
    var reused = create(destination);
    assertThat(reused.statusCode()).isEqualTo(200);
    assertThat(reused.body()).isEqualTo(created.body());
    assertThat(reused.headers().firstValue("Location"))
        .isEqualTo(created.headers().firstValue("Location"));
  }

  private static Stream<Arguments> destinationsAndLocations() {
    return Stream.of(
        Arguments.of(
            "HTTP://EXAMPLE.invalid:80/a%2fb/%41?q=a+b&next=%252F#frag%2f",
            "HTTP://EXAMPLE.invalid:80/a%2fb/%41?q=a+b&next=%252F#frag%2f"),
        Arguments.of(
            "https://example.invalid/café/🚀?q=東京#é",
            "https://example.invalid/caf%C3%A9/%F0%9F%9A%80?q=%E6%9D%B1%E4%BA%AC#%C3%A9"),
        Arguments.of(
            "https://example.invalid/cafe\u0301/%c3%a9?q=e\u0301#e\u0301",
            "https://example.invalid/cafe%CC%81/%c3%a9?q=e%CC%81#e%CC%81"),
        Arguments.of(
            "https://example.com/" + "b".repeat(2028), "https://example.com/" + "b".repeat(2028)),
        Arguments.of(
            "https://example.com/" + "界".repeat(2028),
            "https://example.com/" + "%E7%95%8C".repeat(2028)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Missing123",
        "short",
        "12345678901",
        "Abc_def123",
        "Abc-def123",
        "Abcdef123%C3%A9"
      })
  void unknownAndMalformedCodesReturnSafeUncacheable404(String code) throws Exception {
    var response = follow(code, "GET");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.headers().firstValue("Location")).isEmpty();
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    var error = JSON.readTree(response.body());
    assertThat(error.get("status").intValue()).isEqualTo(404);
    assertThat(error.get("title").stringValue()).isEqualTo("Not Found");
    assertThat(error.get("detail").stringValue()).isEqualTo("Short link not found.");
    assertThat(response.body()).doesNotContain("SELECT", "Exception", "jdbc:");
  }

  @Test
  void aCreatedLinkRedirectsToItsSavedDestinationWithoutFetchingIt() throws Exception {
    String destination = "https://example.invalid/docs?b=2&a=1#Section";
    var created = create(destination);
    assertThat(created.statusCode()).isEqualTo(201);
    String code = JSON.readTree(created.body()).get("code").stringValue();

    var response = follow(code, "GET");

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location")).contains(destination);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(response.body()).isEmpty();
  }

  private HttpResponse<String> create(String destination) throws Exception {
    return send(
        HttpRequest.newBuilder(endpoint("/api/v1/links"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    JSON.createObjectNode().put("destinationUrl", destination).toString())));
  }

  private HttpResponse<String> follow(String code, String method) throws Exception {
    return send(
        HttpRequest.newBuilder(endpoint("/r/" + code))
            .method(method, HttpRequest.BodyPublishers.noBody()));
  }

  private URI endpoint(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    try (var client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(3))
            .build()) {
      return client.send(
          request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }
  }
}
