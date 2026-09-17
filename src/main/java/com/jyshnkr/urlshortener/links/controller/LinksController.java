package com.jyshnkr.urlshortener.links.controller;

import com.jyshnkr.urlshortener.links.exception.CreationFailure;
import com.jyshnkr.urlshortener.links.model.CreateLinkRequest;
import com.jyshnkr.urlshortener.links.model.CreateLinkResponse;
import com.jyshnkr.urlshortener.links.service.LinkCreationService;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class LinksController {

  private final LinkCreationService links;

  LinksController(LinkCreationService links) {
    this.links = links;
  }

  @PostMapping("/api/v1/links")
  ResponseEntity<CreateLinkResponse> create(
      @RequestHeader HttpHeaders headers, @RequestBody CreateLinkRequest request) {
    if (headers.containsHeader("Idempotency-Key")) {
      throw new CreationFailure(CreationFailure.Reason.INVALID_INPUT,
          "Remove the Idempotency-Key header; provide only destinationUrl in the JSON body.");
    }
    var outcome = links.create(request.destinationUrl());
    var link = outcome.link();
    return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .location(URI.create(link.shortUrl()))
        .body(new CreateLinkResponse(link.code(), link.shortUrl(), link.destinationUrl()));
  }
}
