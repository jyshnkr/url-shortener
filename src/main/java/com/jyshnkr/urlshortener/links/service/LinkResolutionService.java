package com.jyshnkr.urlshortener.links.service;

import com.jyshnkr.urlshortener.links.dao.LinkStore;
import com.jyshnkr.urlshortener.links.exception.LinkFailure;
import com.jyshnkr.urlshortener.links.model.Link;
import com.jyshnkr.urlshortener.links.validation.ShortCodeValidator;

public class LinkResolutionService {

  private final LinkStore store;

  public LinkResolutionService(LinkStore store) {
    this.store = store;
  }

  public Link resolve(String code) {
    ShortCodeValidator.requireValid(code);
    return store.findByCode(code).orElseThrow(LinkResolutionService::notFound);
  }

  private static LinkFailure notFound() {
    return new LinkFailure(LinkFailure.Reason.NOT_FOUND, "Short link not found.");
  }
}
