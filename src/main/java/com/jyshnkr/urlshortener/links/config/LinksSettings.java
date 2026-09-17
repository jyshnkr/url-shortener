package com.jyshnkr.urlshortener.links.config;

import java.net.URI;
import java.net.URISyntaxException;

public record LinksSettings(String baseUrl) {

  public LinksSettings {
    baseUrl = validateBaseUrl(baseUrl);
  }

  private static String validateBaseUrl(String baseUrl) {
    try {
      URI base = new URI(baseUrl);
      if (("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
          && base.getHost() != null && base.getRawUserInfo() == null
          && base.getRawQuery() == null && base.getRawFragment() == null && base.getPort() <= 65535) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
      }
    } catch (URISyntaxException | NullPointerException ignored) {
      // Configuration errors must not echo a potentially sensitive value.
    }
    throw new IllegalArgumentException("shortener.base-url must be an HTTP(S) base URL without credentials, query or fragment.");
  }

}
