package com.jyshnkr.urlshortener.links.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.jyshnkr.urlshortener.links.model.RedirectEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class RedirectEventFormatTest {

  @Test
  void anEventIsWrittenAsJsonFieldsAndReadBackUnchanged() {
    var event =
        new RedirectEvent(
            UUID.fromString("3f1c6c5e-8a43-4c9b-9a55-2b0b7f5d9e01"),
            "Abcdef1234",
            Instant.parse("2026-10-25T12:00:00.123456Z"));

    String message = RedirectEventFormat.write(event);

    var json = JsonMapper.builder().build().readTree(message);
    assertThat(json.get("eventId").stringValue()).isEqualTo("3f1c6c5e-8a43-4c9b-9a55-2b0b7f5d9e01");
    assertThat(json.get("code").stringValue()).isEqualTo("Abcdef1234");
    assertThat(json.get("redirectedAt").stringValue()).isEqualTo("2026-10-25T12:00:00.123456Z");
    assertThat(RedirectEventFormat.read(message)).contains(event);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "not json",
        "null",
        "[]",
        "{}",
        "{\"eventId\":\"not-a-uuid\",\"code\":\"Abcdef1234\",\"redirectedAt\":\"2026-10-25T12:00:00Z\"}",
        "{\"eventId\":7,\"code\":\"Abcdef1234\",\"redirectedAt\":\"2026-10-25T12:00:00Z\"}",
        "{\"eventId\":\"3f1c6c5e-8a43-4c9b-9a55-2b0b7f5d9e01\",\"code\":\"Abcdef123!\",\"redirectedAt\":\"2026-10-25T12:00:00Z\"}",
        "{\"eventId\":\"3f1c6c5e-8a43-4c9b-9a55-2b0b7f5d9e01\",\"code\":\"Abcdef1234\",\"redirectedAt\":\"yesterday\"}",
        "{\"eventId\":\"3f1c6c5e-8a43-4c9b-9a55-2b0b7f5d9e01\",\"code\":\"Abcdef1234\"}",
        // Valid instants that PostgreSQL could not store, so no retry could ever save them.
        "{\"eventId\":\"3f1c6c5e-8a43-4c9b-9a55-2b0b7f5d9e01\",\"code\":\"Abcdef1234\",\"redirectedAt\":\"+10000-01-01T00:00:00Z\"}",
        "{\"eventId\":\"3f1c6c5e-8a43-4c9b-9a55-2b0b7f5d9e01\",\"code\":\"Abcdef1234\",\"redirectedAt\":\"0000-12-31T23:59:59Z\"}",
        "{\"eventId\":\"3f1c6c5e-8a43-4c9b-9a55-2b0b7f5d9e01\",\"code\":\"Abcdef1234\",\"redirectedAt\":\"+1000000000-12-31T23:59:59.999999999Z\"}",
        "{\"eventId\":\"3f1c6c5e-8a43-4c9b-9a55-2b0b7f5d9e01\",\"code\":\"Abcdef1234\",\"redirectedAt\":\"-1000000000-01-01T00:00:00Z\"}"
      })
  void messagesThisServiceCouldNotHavePublishedAreRejected(String message) {
    assertThat(RedirectEventFormat.read(message)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0001-01-01T00:00:00Z", "9999-12-31T23:59:59.999999999Z"})
  void timestampsAtTheEdgesOfTheStorableRangeAreAccepted(String redirectedAt) {
    String message =
        "{\"eventId\":\"3f1c6c5e-8a43-4c9b-9a55-2b0b7f5d9e01\",\"code\":\"Abcdef1234\",\"redirectedAt\":\""
            + redirectedAt
            + "\"}";

    assertThat(RedirectEventFormat.read(message))
        .hasValueSatisfying(
            event -> assertThat(event.redirectedAt()).isEqualTo(Instant.parse(redirectedAt)));
  }
}
