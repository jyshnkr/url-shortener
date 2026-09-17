package com.jyshnkr.urlshortener.links.validation;

import com.jyshnkr.urlshortener.links.exception.LinkFailure;

public final class ShortCodeValidator {
  public static void requireValid(String code) {
    if (code == null || !code.matches("[A-Za-z0-9]{10}")) {
      throw new LinkFailure(LinkFailure.Reason.NOT_FOUND, "Short link not found.");
    }
  }

  private ShortCodeValidator() {}
}
