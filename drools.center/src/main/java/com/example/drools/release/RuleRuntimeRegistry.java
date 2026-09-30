package com.example.drools.release;

import com.example.drools.runtime.CompiledRuleBundle;
import com.example.drools.runtime.RuleBundleCompiler;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.PreDestroy;
import org.kie.api.KieServices;
import org.kie.api.conf.EventProcessingOption;
import org.springframework.stereotype.Component;

/** Executions retain their exact release. A slow refresh does not block other rule types. */
@Component
public class RuleRuntimeRegistry {
  private final RuleReleaseService releases;
  private final RuleBundleCompiler compiler = new RuleBundleCompiler();
  private final MeterRegistry metrics;
  private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  public RuleRuntimeRegistry(RuleReleaseService releases) {
    this(releases, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  }

  @org.springframework.beans.factory.annotation.Autowired
  public RuleRuntimeRegistry(RuleReleaseService releases, MeterRegistry metrics) {
    this.releases = releases;
    this.metrics = metrics;
    Gauge.builder("rules.runtime.types", slots, java.util.Map::size).register(metrics);
  }

  public Lease acquire(String type) {
    if (closed.get()) throw new IllegalStateException("Rule runtime is closed");
    Slot slot = slots.computeIfAbsent(type, ignored -> new Slot());
    synchronized (slot) {
      if (closed.get()) throw new IllegalStateException("Rule runtime is closed");
      JsonNode published = releases.current(type);
      Generation generation = slot.current;
      if (generation == null
          || generation.bundle.path("releaseId").asLong() != published.path("releaseId").asLong()) {
        org.kie.api.KieBaseConfiguration configuration =
            KieServices.Factory.get().newKieBaseConfiguration();
        configuration.setOption(
            EventProcessingOption.valueOf(
                published
                    .path("eventProcessingMode")
                    .asText("stream")
                    .toUpperCase(java.util.Locale.ROOT)));
        Generation replacement;
        Timer.Sample sample = Timer.start(metrics);
        try {
          replacement = new Generation(compiler.compile(published, configuration), published);
        } catch (RuntimeException failure) {
          metrics.counter("rules.runtime.compile.failures").increment();
          throw failure;
        } finally {
          sample.stop(metrics.timer("rules.runtime.compile.duration"));
        }
        slot.current = replacement;
        if (generation != null) {
          generation.retired = true;
          disposeIfUnused(generation);
        }
        generation = replacement;
      }
      metrics.counter("rules.runtime.acquisitions").increment();
      generation.references++;
      return new Lease(slot, generation);
    }
  }

  private void disposeIfUnused(Generation generation) {
    if (generation.retired && generation.references == 0) generation.runtime.close();
  }

  @PreDestroy
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    for (Slot slot : slots.values()) {
      synchronized (slot) {
        if (slot.current != null) {
          slot.current.retired = true;
          disposeIfUnused(slot.current);
        }
      }
    }
    slots.clear();
  }

  private static final class Slot {
    Generation current;
  }

  private static final class Generation {
    final CompiledRuleBundle runtime;
    final JsonNode bundle;
    int references;
    boolean retired;

    Generation(CompiledRuleBundle runtime, JsonNode bundle) {
      this.runtime = runtime;
      this.bundle = bundle.deepCopy();
    }
  }

  public final class Lease implements AutoCloseable {
    private final Slot slot;
    private final Generation generation;
    private boolean closed;

    private Lease(Slot slot, Generation generation) {
      this.slot = slot;
      this.generation = generation;
    }

    public CompiledRuleBundle runtime() {
      return generation.runtime;
    }

    public JsonNode bundle() {
      return generation.bundle.deepCopy();
    }

    @Override
    public void close() {
      synchronized (slot) {
        if (!closed) {
          closed = true;
          generation.references--;
          disposeIfUnused(generation);
        }
      }
    }
  }
}
