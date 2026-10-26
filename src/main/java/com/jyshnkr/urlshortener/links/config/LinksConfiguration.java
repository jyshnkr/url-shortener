package com.jyshnkr.urlshortener.links.config;

import com.jyshnkr.urlshortener.links.analytics.JdbcAnalyticsWriter;
import com.jyshnkr.urlshortener.links.analytics.KafkaRedirectPublisher;
import com.jyshnkr.urlshortener.links.analytics.RecordingSettings;
import com.jyshnkr.urlshortener.links.analytics.RedirectEventConsumer;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
class LinksConfiguration {
  private static final String TRANSPORT = "shortener.analytics.transport";

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = TRANSPORT, havingValue = "in-process")
  RedirectRecorder redirectRecorder(HikariDataSource primary, MeterRegistry metrics) {
    return new RedirectRecorder(
        new JdbcAnalyticsWriter(primary), metrics, Clock.systemUTC(), RecordingSettings.defaults());
  }

  @Bean
  @ConditionalOnProperty(name = TRANSPORT, havingValue = "kafka", matchIfMissing = true)
  KafkaRedirectPublisher kafkaRedirectPublisher(
      KafkaTemplate<String, String> template,
      @Value("${shortener.analytics.kafka.topic}") String topic,
      MeterRegistry metrics) {
    var publisher = new KafkaRedirectPublisher(template, topic, metrics, Clock.systemUTC());
    // Bean creation precedes the web server start, so this runs before redirects arrive.
    publisher.awaitTopicMetadata(KafkaRedirectPublisher.STARTUP_METADATA_WAIT);
    return publisher;
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = TRANSPORT, havingValue = "kafka", matchIfMissing = true)
  RedirectEventConsumer redirectEventConsumer(HikariDataSource primary, MeterRegistry metrics) {
    return new RedirectEventConsumer(new JdbcAnalyticsWriter(primary), metrics);
  }

  /**
   * Retries a failed batch from memory every second until it is saved. The default handler gives up
   * after ten attempts and commits the offsets, which would lose those events. Events that no retry
   * could save are skipped before they reach the database write, so this covers temporary failures.
   */
  @Bean
  @ConditionalOnProperty(name = TRANSPORT, havingValue = "kafka", matchIfMissing = true)
  DefaultErrorHandler redirectEventRetries() {
    return new DefaultErrorHandler(new FixedBackOff(1_000L, FixedBackOff.UNLIMITED_ATTEMPTS));
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
