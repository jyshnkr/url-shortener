package com.jyshnkr.urlshortener.links.analytics;

import com.jyshnkr.urlshortener.links.dao.LinkAnalyticsStore;
import com.jyshnkr.urlshortener.links.model.RedirectIncrement;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Properties;

public final class JdbcAnalyticsWriter implements AnalyticsBatchWriter {
  private final HikariDataSource pool;
  private final LinkAnalyticsStore store;

  public JdbcAnalyticsWriter(HikariDataSource primary) {
    // No-arg construction is lazy. Do not expose this owned pool as a Spring DataSource bean:
    // doing so would disable Boot's normal DataSource auto-configuration.
    pool = new HikariDataSource();
    pool.setPoolName("link-analytics-writer");
    pool.setJdbcUrl(primary.getJdbcUrl());
    pool.setUsername(primary.getUsername());
    pool.setPassword(primary.getPassword());
    pool.setDriverClassName(primary.getDriverClassName());
    var properties = new Properties();
    properties.putAll(primary.getDataSourceProperties());
    pool.setDataSourceProperties(properties);
    pool.setConnectionTimeout(primary.getConnectionTimeout());
    pool.setValidationTimeout(primary.getValidationTimeout());
    pool.setMaximumPoolSize(1);
    pool.setMinimumIdle(0);
    pool.setInitializationFailTimeout(-1);
    store = new LinkAnalyticsStore(pool);
  }

  @Override
  public void write(List<RedirectIncrement> increments) {
    store.increment(increments);
  }

  @Override
  public void close() {
    pool.close();
  }
}
