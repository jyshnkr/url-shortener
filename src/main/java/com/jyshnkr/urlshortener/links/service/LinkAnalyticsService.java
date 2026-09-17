package com.jyshnkr.urlshortener.links.service;

import com.jyshnkr.urlshortener.links.dao.LinkAnalyticsStore;
import com.jyshnkr.urlshortener.links.exception.LinkFailure;
import com.jyshnkr.urlshortener.links.model.LinkStats;
import com.jyshnkr.urlshortener.links.validation.ShortCodeValidator;

public final class LinkAnalyticsService {
  private final LinkAnalyticsStore store;

  public LinkAnalyticsService(LinkAnalyticsStore store) {
    this.store = store;
  }

  public LinkStats stats(String code) {
    ShortCodeValidator.requireValid(code);
    return store
        .findByCode(code)
        .orElseThrow(() -> new LinkFailure(LinkFailure.Reason.NOT_FOUND, "Short link not found."));
  }
}
