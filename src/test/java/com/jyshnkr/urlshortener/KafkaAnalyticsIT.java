package com.jyshnkr.urlshortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.jyshnkr.urlshortener.links.analytics.KafkaRedirectPublisher;
import com.jyshnkr.urlshortener.links.analytics.RedirectEventConsumer;
import com.jyshnkr.urlshortener.links.analytics.RedirectEventFormat;
import com.jyshnkr.urlshortener.links.model.RedirectEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The default Kafka transport, end to end through a disposable broker and database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class KafkaAnalyticsIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  // Includes the consumer joining its group on a fresh broker.
  private static final Duration RECORDING_WAIT = Duration.ofSeconds(20);

  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @LocalServerPort private int port;
  @Autowired private DataSource database;
  @Autowired private KafkaRedirectPublisher publisher;
  @Autowired private RedirectEventConsumer consumer;
  @Autowired private KafkaTemplate<String, String> kafka;
  @Autowired private KafkaListenerEndpointRegistry listeners;
  @Autowired private KafkaContainer broker;

  @Value("${shortener.analytics.kafka.topic}")
  private String topic;

  @Value("${spring.kafka.consumer.group-id}")
  private String group;

  @AfterEach
  void closeClient() {
    client.close();
  }

  @Test
  void onlyGetRedirectsArePublishedKeyedByCodeAndEachIsSavedOnce() throws Exception {
    String code = createLink();
    long submitted = publisher.diagnostics().submitted();
    Instant before = Instant.now().minusMillis(1);

    assertThat(send("/r/" + code, "HEAD").statusCode()).isEqualTo(302);
    for (int index = 0; index < 3; index++) {
      assertThat(send("/r/" + code, "GET").statusCode()).isEqualTo(302);
    }
    assertThat(send("/r/Absent1234", "GET").statusCode()).isEqualTo(404);
    assertThat(send("/api/v1/links/" + code + "/stats", "GET").statusCode()).isEqualTo(200);

    awaitCount(code, 3);
    assertThat(publisher.diagnostics().submitted() - submitted).isEqualTo(3);
    var stats = stats(code);
    assertThat(stats.get("lastRedirectedAt").stringValue()).endsWith("Z");
    assertThat(Instant.parse(stats.get("lastRedirectedAt").stringValue())).isAfterOrEqualTo(before);
    await()
        .during(Duration.ofMillis(600))
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(() -> assertThat(count(code)).isEqualTo(3));

    var messages = topicRecordsFor(code, 3);
    assertThat(messages).hasSize(3);
    var events =
        messages.stream()
            .map(message -> RedirectEventFormat.read(message.value()).orElseThrow())
            .toList();
    assertThat(events).extracting(RedirectEvent::code).containsOnly(code);
    assertThat(events).extracting(RedirectEvent::eventId).doesNotHaveDuplicates();
  }

  @Test
  void theSameEventPublishedTwiceIsCountedOnce() throws Exception {
    String code = createLink();
    var event =
        new RedirectEvent(UUID.randomUUID(), code, Instant.parse("2026-10-01T12:00:00.123456Z"));
    long duplicates = consumer.diagnostics().duplicates();

    String message = RedirectEventFormat.write(event);
    kafka.send(topic, code, message).get(10, TimeUnit.SECONDS);
    kafka.send(topic, code, message).get(10, TimeUnit.SECONDS);

    await()
        .atMost(RECORDING_WAIT)
        .untilAsserted(
            () -> {
              assertThat(consumer.diagnostics().duplicates()).isGreaterThan(duplicates);
              assertThat(count(code)).isEqualTo(1);
            });
    var stats = stats(code);
    assertThat(Instant.parse(stats.get("lastRedirectedAt").stringValue()))
        .isEqualTo(event.redirectedAt());
    await()
        .during(Duration.ofMillis(600))
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(() -> assertThat(count(code)).isEqualTo(1));
  }

  @Test
  void redeliveringEveryEventAfterAnOffsetResetChangesNoCount() throws Exception {
    String code = createLink();
    for (int index = 0; index < 3; index++) {
      assertThat(send("/r/" + code, "GET").statusCode()).isEqualTo(302);
    }
    awaitCount(code, 3);
    var container = listeners.getListenerContainer(RedirectEventConsumer.LISTENER_ID);
    assertThat(container).isNotNull();
    try (var admin =
        Admin.create(
            Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers()))) {
      var partitions =
          kafka.partitionsFor(topic).stream()
              .map(partition -> new TopicPartition(topic, partition.partition()))
              .toList();
      var end = endOffsets(admin, partitions);
      long published = end.values().stream().mapToLong(Long::longValue).sum();
      // Every record so far is saved and its offset committed.
      await()
          .atMost(RECORDING_WAIT)
          .untilAsserted(() -> assertThat(committedOffsets(admin)).isEqualTo(end));
      var savedCounts = allCounts();
      var before = consumer.diagnostics();

      container.stop();
      try {
        var beginning =
            partitions.stream()
                .collect(Collectors.toMap(partition -> partition, ignored -> offset(0)));
        // The broker accepts the reset once the stopped consumer has left the group.
        await()
            .atMost(RECORDING_WAIT)
            .ignoreExceptions()
            .untilAsserted(
                () ->
                    admin
                        .alterConsumerGroupOffsets(group, beginning)
                        .all()
                        .get(5, TimeUnit.SECONDS));
      } finally {
        container.start();
      }

      // Every replayed event is recognized as already counted; none is counted again.
      await()
          .atMost(RECORDING_WAIT)
          .untilAsserted(
              () -> {
                var now = consumer.diagnostics();
                long replayed = now.received() - before.received();
                assertThat(replayed).isGreaterThanOrEqualTo(published);
                assertThat(now.duplicates() - before.duplicates()).isEqualTo(replayed);
                assertThat(now.recorded()).isEqualTo(before.recorded());
              });
      assertThat(allCounts()).isEqualTo(savedCounts);
    }
  }

  @Test
  void aBlockedDatabaseWriteIsRetriedUntilSavedWithoutLosingTheEventOrDelayingRedirects()
      throws Exception {
    String code = createLink();
    String other = createLink();
    assertThat(send("/r/" + code, "GET").statusCode()).isEqualTo(302);
    awaitCount(code, 1);
    long failures = consumer.diagnostics().failedWrites();
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
            .atMost(RECORDING_WAIT)
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
              assertThat(count(code)).isEqualTo(1);
            });
        // The write times out and is retried; offsets stay uncommitted, so nothing is lost.
        await()
            .atMost(RECORDING_WAIT)
            .until(() -> consumer.diagnostics().failedWrites() > failures);
        assertThat(count(code)).isEqualTo(1);
      } finally {
        lock.rollback();
      }
    }
    // No further redirect: the retried batch itself supplies the second count.
    awaitCount(code, 2);
    awaitCount(other, 1);
  }

  @Test
  void simultaneousRedirectsAreAllCountedOnce() throws Exception {
    String code = createLink();
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
        task.get(30, TimeUnit.SECONDS);
      }
    }
    awaitCount(code, 80);
    await()
        .during(Duration.ofMillis(600))
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(() -> assertThat(count(code)).isEqualTo(80));
  }

  private List<ConsumerRecord<String, String>> topicRecordsFor(String code, int expected) {
    var found = new ArrayList<ConsumerRecord<String, String>>();
    try (var reader =
        new KafkaConsumer<String, String>(
            Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                broker.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "test-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class))) {
      reader.subscribe(List.of(topic));
      long deadline = System.nanoTime() + RECORDING_WAIT.toNanos();
      while (found.size() < expected && System.nanoTime() < deadline) {
        for (var message : reader.poll(Duration.ofMillis(200))) {
          if (code.equals(message.key())) {
            found.add(message);
          }
        }
      }
    }
    return found;
  }

  private Map<TopicPartition, Long> endOffsets(Admin admin, List<TopicPartition> partitions)
      throws Exception {
    var latest =
        partitions.stream()
            .collect(Collectors.toMap(partition -> partition, ignored -> OffsetSpec.latest()));
    var offsets = new HashMap<TopicPartition, Long>();
    admin
        .listOffsets(latest)
        .all()
        .get(10, TimeUnit.SECONDS)
        .forEach(
            (partition, info) -> {
              if (info.offset() > 0) {
                offsets.put(partition, info.offset());
              }
            });
    return offsets;
  }

  private Map<TopicPartition, Long> committedOffsets(Admin admin) throws Exception {
    var offsets = new HashMap<TopicPartition, Long>();
    admin
        .listConsumerGroupOffsets(group)
        .partitionsToOffsetAndMetadata()
        .get(10, TimeUnit.SECONDS)
        .forEach(
            (partition, committed) -> {
              if (committed != null && committed.offset() > 0) {
                offsets.put(partition, committed.offset());
              }
            });
    return offsets;
  }

  private static OffsetAndMetadata offset(long value) {
    return new OffsetAndMetadata(value);
  }

  private Map<String, Long> allCounts() {
    var counts = new HashMap<String, Long>();
    new JdbcTemplate(database)
        .query(
            "SELECT short_code, redirect_count FROM link_analytics",
            row -> {
              counts.put(row.getString("short_code"), row.getLong("redirect_count"));
            });
    return counts;
  }

  private String createLink() throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(endpoint("/api/v1/links"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        JSON.createObjectNode()
                            .put(
                                "destinationUrl",
                                "https://example.invalid/kafka-analytics/" + UUID.randomUUID())
                            .toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(201);
    return JSON.readTree(response.body()).get("code").stringValue();
  }

  private void awaitCount(String code, long expected) {
    await().atMost(RECORDING_WAIT).untilAsserted(() -> assertThat(count(code)).isEqualTo(expected));
  }

  private long count(String code) throws Exception {
    return stats(code).get("redirectCount").longValue();
  }

  private JsonNode stats(String code) throws Exception {
    var response = send("/api/v1/links/" + code + "/stats", "GET");
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body());
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
