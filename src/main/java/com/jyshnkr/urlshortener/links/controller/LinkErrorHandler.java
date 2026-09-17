package com.jyshnkr.urlshortener.links.controller;

import com.jyshnkr.urlshortener.links.exception.CreationFailure;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = LinksController.class)
class LinkErrorHandler {

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ProblemDetail handleInvalidRequest(Exception exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
        "Provide a JSON object containing only destinationUrl.");
  }

  @ExceptionHandler(CreationFailure.class)
  ProblemDetail handleCreationFailure(CreationFailure failure) {
    HttpStatus status = switch (failure.reason()) {
      case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
      case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
    };
    return ProblemDetail.forStatusAndDetail(status, failure.getMessage());
  }
}
