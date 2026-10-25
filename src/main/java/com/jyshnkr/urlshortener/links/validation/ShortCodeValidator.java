package com.jyshnkr.urlshortener.links.validation;

import com.jyshnkr.urlshortener.links.exception.LinkFailure;

public final class ShortCodeValidator {
  public static void requireValid(String code) {
    if (!isValid(code)) {
      throw new LinkFailure(LinkFailure.Reason.NOT_FOUND, "Short link not found.");
    }
  }

  public static boolean isValid(String code) {
    return code != null && code.matches("[A-Za-z0-9]{10}");
  }

  private ShortCodeValidator() {}
}
