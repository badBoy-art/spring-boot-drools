package com.example.drools.sdk.document;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Evaluate the optional JDBC store only after Boot has registered its DataSource. */
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(DataSourceAutoConfiguration.class)
@ConditionalOnBean(DataSource.class)
public class JdbcKieSessionAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(KieSessionStore.class)
  public KieSessionStore jdbcKieSessionStore(DataSource source) {
    return new JdbcKieSessionStore(source);
  }

  @Bean
  @ConditionalOnMissingBean(KieSessionCoordinator.class)
  public KieSessionCoordinator jdbcKieSessionCoordinator(DataSource source) {
    return new JdbcKieSessionCoordinator(source);
  }
}
