package com.jyshnkr.urlshortener.links.service;

import com.jyshnkr.urlshortener.links.dao.LinkStore;
import com.jyshnkr.urlshortener.links.exception.LinkFailure;
import com.jyshnkr.urlshortener.links.model.Link;

public class LinkResolutionService {

  private final LinkStore store;

  public LinkResolutionService(LinkStore store) {
    this.store = store;
  }

  public Link resolve(String code) {
    if (code == null || !code.matches("[A-Za-z0-9]{10}")) {
      throw notFound();
    }
    return store.findByCode(code).orElseThrow(LinkResolutionService::notFound);
  }

  private static LinkFailure notFound() {
    return new LinkFailure(LinkFailure.Reason.NOT_FOUND, "Short link not found.");
  }
}
