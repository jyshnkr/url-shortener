package com.jyshnkr.urlshortener.links.controller;

import com.jyshnkr.urlshortener.links.exception.LinkFailure;
import com.jyshnkr.urlshortener.links.model.CreateLinkRequest;
import com.jyshnkr.urlshortener.links.model.CreateLinkResponse;
import com.jyshnkr.urlshortener.links.service.LinkCreationService;
import com.jyshnkr.urlshortener.links.service.LinkResolutionService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class LinksController {

  private final LinkCreationService links;
  private final LinkResolutionService resolution;

  LinksController(LinkCreationService links, LinkResolutionService resolution) {
    this.links = links;
    this.resolution = resolution;
  }

  @GetMapping("/r/{code}")
  ResponseEntity<Void> redirect(@PathVariable String code) {
    var link = resolution.resolve(code);
    return ResponseEntity.status(HttpStatus.FOUND)
        .header(HttpHeaders.LOCATION, destinationHeader(link.destinationUrl()))
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .build();
  }

  @PostMapping("/api/v1/links")
  ResponseEntity<CreateLinkResponse> create(
      @RequestHeader HttpHeaders headers, @RequestBody CreateLinkRequest request) {
    if (headers.containsHeader("Idempotency-Key")) {
      throw new LinkFailure(
          LinkFailure.Reason.INVALID_INPUT,
          "Remove the Idempotency-Key header; provide only destinationUrl in the JSON body.");
    }
    var outcome = links.create(request.destinationUrl());
    var link = outcome.link();
    return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .location(URI.create(link.shortUrl()))
        .body(new CreateLinkResponse(link.code(), link.shortUrl(), link.destinationUrl()));
  }

  private static String destinationHeader(String destination) {
    // Encode only non-ASCII UTF-8 bytes: preserve escapes and decomposed Unicode exactly.
    var header = new StringBuilder();
    for (byte value : destination.getBytes(StandardCharsets.UTF_8)) {
      int unsigned = Byte.toUnsignedInt(value);
      if (unsigned < 128) {
        header.append((char) unsigned);
      } else {
        header
            .append('%')
            .append("0123456789ABCDEF".charAt(unsigned >>> 4))
            .append("0123456789ABCDEF".charAt(unsigned & 15));
      }
    }
    return header.toString();
  }
}
