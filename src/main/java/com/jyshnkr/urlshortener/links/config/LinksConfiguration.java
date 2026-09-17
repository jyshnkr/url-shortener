package com.jyshnkr.urlshortener.links.config;

import com.jyshnkr.urlshortener.links.dao.LinkStore;
import com.jyshnkr.urlshortener.links.generator.ShortCodeGenerator;
import com.jyshnkr.urlshortener.links.service.LinkCreationService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class LinksConfiguration {

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
  LinkCreationService linkCreationService(
      LinkStore store, LinksSettings settings, ShortCodeGenerator generator) {
    return new LinkCreationService(store, settings, generator);
  }
}
