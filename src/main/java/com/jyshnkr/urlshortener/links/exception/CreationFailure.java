package com.jyshnkr.urlshortener.links.exception;

public class CreationFailure extends RuntimeException {

  public enum Reason { INVALID_INPUT, UNAVAILABLE }

  private final Reason reason;

  public CreationFailure(Reason reason, String detail) {
    super(detail);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
