package com.jyshnkr.urlshortener.links.model;

import java.time.Instant;

public record LinkStats(String code, long redirectCount, Instant lastRedirectedAt) {}
