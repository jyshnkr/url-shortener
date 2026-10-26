package com.jyshnkr.urlshortener.links.model;

/**
 * Outcome of saving a batch of redirect events: how many were newly counted, and how many events,
 * repeated copies included, were skipped because their short code has no saved link, so no retry
 * could ever count them.
 */
public record RedirectEventBatchResult(int counted, int unknownLinks) {}
