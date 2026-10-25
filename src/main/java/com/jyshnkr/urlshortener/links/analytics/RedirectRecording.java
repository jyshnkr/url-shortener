package com.jyshnkr.urlshortener.links.analytics;

/**
 * Accepts one GET redirect for usage recording. Implementations bound their waiting and never
 * throw, so recording problems cannot prevent the redirect.
 */
public interface RedirectRecording {
  void record(String code);
}
