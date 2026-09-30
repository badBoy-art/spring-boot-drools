package com.example.drools.worker;

import com.example.drools.sdk.document.KieSessionSnapshotObserver;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WorkerObservabilityConfiguration {
  @Bean
  @ConditionalOnMissingBean(KieSessionSnapshotObserver.class)
  public KieSessionSnapshotObserver snapshotObserver(MeterRegistry metrics) {
    return new KieSessionSnapshotObserver() {
      @Override
      public void onCheckpoint(String id, long bytes, long nanos) {
        metrics.summary("rules.session.checkpoint.bytes").record(bytes);
        metrics.timer("rules.session.checkpoint.duration").record(nanos, TimeUnit.NANOSECONDS);
      }

      @Override
      public void onRestore(String id, long bytes, long nanos) {
        metrics.timer("rules.session.restore.duration").record(nanos, TimeUnit.NANOSECONDS);
        metrics.counter("rules.session.restores").increment();
      }

      @Override
      public void onLeaseLost(String id, String owner) {
        metrics.counter("rules.session.lease.lost").increment();
      }
    };
  }
}
