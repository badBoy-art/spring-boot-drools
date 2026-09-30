package com.example.drools.sdk.document;

import com.example.drools.sdk.document.feign.RuleTypeConfigFeignClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Import this configuration in a business service to activate the SDK's Rule Center Feign client.
 */
@Configuration
@EnableFeignClients(clients = RuleTypeConfigFeignClient.class)
public class DocumentRuleSdkConfiguration {
  @Bean(destroyMethod = "close")
  public DroolsRuleCenterSdk documentDroolsRuleCenterSdk(
      RuleTypeConfigFeignClient client,
      ObjectProvider<KieSessionStore> storeProvider,
      ObjectProvider<KieSessionLifecycle> lifecycleProvider,
      ObjectProvider<KieSessionCoordinator> coordinatorProvider,
      ObjectProvider<KieSessionSnapshotObserver> observerProvider,
      @Value("${drools.session.node-id:${HOSTNAME:local}}") String nodeId,
      @Value("${drools.session.lease-millis:30000}") long leaseMillis,
      @Value("${drools.session.max-snapshot-bytes:67108864}") long maxSnapshotBytes) {
    return new DroolsRuleCenterSdk(
        client,
        storeProvider.getIfAvailable(),
        lifecycleProvider.getIfAvailable(),
        coordinatorProvider.getIfAvailable(),
        nodeId,
        leaseMillis,
        maxSnapshotBytes,
        observerProvider.getIfAvailable());
  }
}
