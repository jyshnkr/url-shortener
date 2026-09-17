package com.jyshnkr.urlshortener.links.controller;

import com.jyshnkr.urlshortener.links.exception.LinkFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = LinksController.class)
class LinkErrorHandler {

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ProblemDetail handleInvalidRequest(Exception exception) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST, "Provide a JSON object containing only destinationUrl.");
  }

  @ExceptionHandler(LinkFailure.class)
  ResponseEntity<ProblemDetail> handleLinkFailure(LinkFailure failure, HttpServletRequest request) {
    HttpStatus status =
        switch (failure.reason()) {
          case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    var response = ResponseEntity.status(status);
    // GET and Spring's automatic HEAD handler are the resolution operations in this controller.
    if ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())) {
      response.header(HttpHeaders.CACHE_CONTROL, "no-store");
    }
    return response.body(ProblemDetail.forStatusAndDetail(status, failure.getMessage()));
  }
}
