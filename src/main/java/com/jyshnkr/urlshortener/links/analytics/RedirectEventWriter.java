package com.jyshnkr.urlshortener.links.analytics;

import com.jyshnkr.urlshortener.links.model.RedirectEvent;
import com.jyshnkr.urlshortener.links.model.RedirectEventBatchResult;
import java.util.List;

/**
 * Storage boundary for the Kafka transport. Counts only events whose IDs were not recorded before,
 * atomically with the counter update; an exception means nothing from the call was saved.
 */
public interface RedirectEventWriter extends AutoCloseable {
  /**
   * Returns how many events were newly counted and how many were skipped because their short code
   * has no saved link; the rest were already recorded.
   */
  RedirectEventBatchResult recordNewEvents(List<RedirectEvent> events);

  @Override
  void close();
}
