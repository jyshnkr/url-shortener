package com.jyshnkr.urlshortener.links.service;

import com.jyshnkr.urlshortener.links.config.LinksSettings;
import com.jyshnkr.urlshortener.links.dao.LinkStore;
import com.jyshnkr.urlshortener.links.exception.LinkFailure;
import com.jyshnkr.urlshortener.links.model.CreationOutcome;
import com.jyshnkr.urlshortener.links.model.Link;
import com.jyshnkr.urlshortener.links.validation.DestinationUrlValidator;
import java.util.function.Supplier;

public class LinkCreationService {

  private final LinkStore store;
  private final LinksSettings settings;
  private final Supplier<String> shortCodeGenerator;

  public LinkCreationService(
      LinkStore store, LinksSettings settings, Supplier<String> shortCodeGenerator) {
    this.store = store;
    this.settings = settings;
    this.shortCodeGenerator = shortCodeGenerator;
  }

  public CreationOutcome create(String destinationUrl) {
    DestinationUrlValidator.validate(destinationUrl);
    var previous = store.findDestination(destinationUrl);
    if (previous.isPresent()) {
      return new CreationOutcome(previous.get(), false);
    }
    for (int attempt = 0; attempt < 5; attempt++) {
      String code = shortCodeGenerator.get();
      var result = new Link(code, settings.baseUrl() + "/r/" + code, destinationUrl);
      if (store.insertIfAvailable(result)) {
        return new CreationOutcome(result, true);
      }
      // A concurrent insert may have committed this destination; otherwise retry the code.
      previous = store.findDestination(destinationUrl);
      if (previous.isPresent()) {
        return new CreationOutcome(previous.get(), false);
      }
    }
    throw new LinkFailure(LinkFailure.Reason.UNAVAILABLE, "Could not allocate a short code.");
  }
}
