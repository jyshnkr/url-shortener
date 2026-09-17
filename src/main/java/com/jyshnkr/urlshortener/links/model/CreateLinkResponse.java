package com.jyshnkr.urlshortener.links.model;

public record CreateLinkResponse(String code, String shortUrl, String destinationUrl) {}
