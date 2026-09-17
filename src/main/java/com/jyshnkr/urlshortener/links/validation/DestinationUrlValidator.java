package com.jyshnkr.urlshortener.links.validation;

import com.jyshnkr.urlshortener.links.exception.CreationFailure;
import java.net.URI;
import java.net.URISyntaxException;

public final class DestinationUrlValidator {

  private DestinationUrlValidator() {}

  public static void validate(String destinationUrl) {
    // codePoints combines valid UTF-16 pairs; any remaining surrogate is malformed.
    if (destinationUrl == null || destinationUrl.isEmpty() || destinationUrl.length() > 2048
        || destinationUrl.codePoints().anyMatch(character -> Character.isWhitespace(character)
            || Character.isSpaceChar(character) || Character.isISOControl(character)
            || (character >= Character.MIN_SURROGATE && character <= Character.MAX_SURROGATE))) {
      throw invalidDestination();
    }
    try {
      URI destination = new URI(destinationUrl);
      if (!("http".equalsIgnoreCase(destination.getScheme())
          || "https".equalsIgnoreCase(destination.getScheme()))
          || destination.getHost() == null || destination.getRawUserInfo() != null
          || destination.getPort() > 65535) {
        throw invalidDestination();
      }
    } catch (URISyntaxException exception) {
      throw invalidDestination();
    }
  }

  private static CreationFailure invalidDestination() {
    return new CreationFailure(CreationFailure.Reason.INVALID_INPUT,
        "destinationUrl must be a well-formed absolute HTTP or HTTPS URL with a host, no credentials or raw "
            + "whitespace/control characters, and at most 2048 characters.");
  }

}
