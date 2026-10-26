package com.jyshnkr.urlshortener.links.analytics;

import com.jyshnkr.urlshortener.links.model.RedirectEvent;
import com.jyshnkr.urlshortener.links.validation.ShortCodeValidator;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Kafka message value: {"eventId":"<UUID>","code":"<short code>","redirectedAt":"<UTC>"}. */
public final class RedirectEventFormat {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  // The store passes Instant.toString() to PostgreSQL as text, and PostgreSQL parses that only for
  // years 0001-9999. Instant.parse accepts more, and such an event would fail every write attempt
  // and block the events behind it, so it is rejected here instead.
  private static final Instant EARLIEST_STORABLE = Instant.parse("0001-01-01T00:00:00Z");
  private static final Instant LATEST_STORABLE = Instant.parse("+10000-01-01T00:00:00Z");

  public static String write(RedirectEvent event) {
    return JSON.createObjectNode()
        .put("eventId", event.eventId().toString())
        .put("code", event.code())
        .put("redirectedAt", event.redirectedAt().toString())
        .toString();
  }

  /** Returns empty for anything this service could not have published. */
  public static Optional<RedirectEvent> read(String message) {
    if (message == null) {
      return Optional.empty();
    }
    try {
      var event = JSON.readTree(message);
      String eventId = field(event, "eventId");
      String code = field(event, "code");
      String redirectedAt = field(event, "redirectedAt");
      if (eventId == null || redirectedAt == null || !ShortCodeValidator.isValid(code)) {
        return Optional.empty();
      }
      var at = Instant.parse(redirectedAt);
      if (at.isBefore(EARLIEST_STORABLE) || !at.isBefore(LATEST_STORABLE)) {
        return Optional.empty();
      }
      return Optional.of(new RedirectEvent(UUID.fromString(eventId), code, at));
    } catch (RuntimeException malformed) {
      // Invalid JSON, UUID or timestamp: retrying cannot make the message valid.
      return Optional.empty();
    }
  }

  private static String field(JsonNode event, String name) {
    var value = event.get(name);
    return value == null || !value.isString() ? null : value.stringValue();
  }

  private RedirectEventFormat() {}
}
