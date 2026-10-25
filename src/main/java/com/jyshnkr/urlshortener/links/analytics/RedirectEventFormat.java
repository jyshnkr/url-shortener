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
      return Optional.of(
          new RedirectEvent(UUID.fromString(eventId), code, Instant.parse(redirectedAt)));
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
