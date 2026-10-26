package com.jyshnkr.urlshortener.links.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One accepted GET redirect; the event ID lets a redelivered copy be recognized. */
public record RedirectEvent(UUID eventId, String code, Instant redirectedAt) {
  public RedirectEvent {
    Objects.requireNonNull(eventId);
    Objects.requireNonNull(code);
    Objects.requireNonNull(redirectedAt);
  }
}
