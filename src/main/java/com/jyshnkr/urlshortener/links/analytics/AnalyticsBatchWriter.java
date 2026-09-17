package com.jyshnkr.urlshortener.links.analytics;

import com.jyshnkr.urlshortener.links.model.RedirectIncrement;
import java.util.List;

/** Storage boundary: an exception means the batch's commit could not be confirmed. */
public interface AnalyticsBatchWriter extends AutoCloseable {
  void write(List<RedirectIncrement> increments);

  @Override
  void close();
}
