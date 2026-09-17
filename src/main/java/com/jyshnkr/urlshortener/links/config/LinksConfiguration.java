package com.jyshnkr.urlshortener.links.config;

import com.jyshnkr.urlshortener.links.analytics.JdbcAnalyticsWriter;
import com.jyshnkr.urlshortener.links.analytics.RecordingSettings;
import com.jyshnkr.urlshortener.links.analytics.RedirectRecorder;
import com.jyshnkr.urlshortener.links.dao.LinkAnalyticsStore;
import com.jyshnkr.urlshortener.links.dao.LinkStore;
import com.jyshnkr.urlshortener.links.generator.ShortCodeGenerator;
import com.jyshnkr.urlshortener.links.service.LinkAnalyticsService;
import com.jyshnkr.urlshortener.links.service.LinkCreationService;
import com.jyshnkr.urlshortener.links.service.LinkResolutionService;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class LinksConfiguration {

  @Bean(destroyMethod = "close")
  RedirectRecorder redirectRecorder(HikariDataSource primary, MeterRegistry metrics) {
    return new RedirectRecorder(
        new JdbcAnalyticsWriter(primary), metrics, Clock.systemUTC(), RecordingSettings.defaults());
  }

  @Bean
  LinkAnalyticsService linkAnalyticsService(DataSource dataSource) {
    return new LinkAnalyticsService(new LinkAnalyticsStore(dataSource));
  }

  @Bean
  LinksSettings linksSettings(@Value("${shortener.base-url}") String baseUrl) {
    return new LinksSettings(baseUrl);
  }

  @Bean
  LinkStore linkStore(DataSource dataSource) {
    return new LinkStore(dataSource);
  }

  @Bean
  ShortCodeGenerator shortCodeGenerator() {
    return new ShortCodeGenerator();
  }

  @Bean
  LinkResolutionService linkResolutionService(LinkStore store) {
    return new LinkResolutionService(store);
  }

  @Bean
  LinkCreationService linkCreationService(
      LinkStore store, LinksSettings settings, ShortCodeGenerator generator) {
    return new LinkCreationService(store, settings, generator);
  }
}
