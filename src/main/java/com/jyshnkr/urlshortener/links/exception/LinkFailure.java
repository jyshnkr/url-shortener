package com.jyshnkr.urlshortener.links.exception;

public class LinkFailure extends RuntimeException {

  public enum Reason {
    INVALID_INPUT,
    NOT_FOUND,
    UNAVAILABLE
  }

  private final Reason reason;

  public LinkFailure(Reason reason, String detail) {
    super(detail);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
