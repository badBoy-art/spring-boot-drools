package com.example.drools.sdk.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

public class JdbcKieSessionAutoConfigurationTest {
  @Test
  public void jdbcStoreIsCreatedAfterTheBootDataSource() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class, JdbcKieSessionAutoConfiguration.class))
        .withPropertyValues(
            "spring.datasource.url=jdbc:h2:mem:autoconfig",
            "spring.datasource.driver-class-name=org.h2.Driver")
        .run(
            context -> {
              assertThat(context)
                  .hasSingleBean(KieSessionStore.class)
                  .hasSingleBean(KieSessionCoordinator.class);
            });
  }

  @Test
  public void applicationsWithoutJdbcRetainTheNativeMemoryRuntime() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JdbcKieSessionAutoConfiguration.class))
        .run(
            context -> {
              assertThat(context)
                  .doesNotHaveBean(KieSessionStore.class)
                  .doesNotHaveBean(KieSessionCoordinator.class);
            });
  }
}
