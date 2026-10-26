package com.jyshnkr.urlshortener.links.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

public record RedirectIncrement(String code, long count, Instant lastRedirectedAt) {
  public RedirectIncrement {
    Objects.requireNonNull(code);
    Objects.requireNonNull(lastRedirectedAt);
    if (count <= 0) {
      throw new IllegalArgumentException("Redirect increment must be positive");
    }
  }

  /** Adds counts and keeps the newest time per code, sorted by code for consistent row locks. */
  public static List<RedirectIncrement> combine(List<RedirectIncrement> increments) {
    var combined = new TreeMap<String, RedirectIncrement>();
    for (var increment : increments) {
      combined.merge(
          increment.code(),
          increment,
          (a, b) ->
              new RedirectIncrement(
                  a.code(),
                  a.count() + b.count(),
                  a.lastRedirectedAt().isAfter(b.lastRedirectedAt())
                      ? a.lastRedirectedAt()
                      : b.lastRedirectedAt()));
    }
    return List.copyOf(combined.values());
  }
}
