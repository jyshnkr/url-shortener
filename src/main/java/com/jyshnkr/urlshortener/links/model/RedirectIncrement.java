package com.jyshnkr.urlshortener.links.model;

import java.time.Instant;
import java.util.Objects;

public record RedirectIncrement(String code, long count, Instant lastRedirectedAt) {
  public RedirectIncrement {
    Objects.requireNonNull(code);
    Objects.requireNonNull(lastRedirectedAt);
    if (count <= 0) {
      throw new IllegalArgumentException("Redirect increment must be positive");
    }
  }
}
