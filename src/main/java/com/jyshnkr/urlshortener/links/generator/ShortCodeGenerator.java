package com.jyshnkr.urlshortener.links.generator;

import java.security.SecureRandom;
import java.util.function.Supplier;

public final class ShortCodeGenerator implements Supplier<String> {

  private static final String ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  private final SecureRandom random = new SecureRandom();

  @Override
  public String get() {
    var code = new StringBuilder(10);
    for (int index = 0; index < 10; index++) {
      code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return code.toString();
  }
}
