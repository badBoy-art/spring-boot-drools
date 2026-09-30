package com.example.drools.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieContainer;

/** A built native KIE module. The caller owns its lifecycle, including any child sessions. */
public final class CompiledRuleBundle implements AutoCloseable {
  private final KieContainer container;
  private final KieBase base;
  private final AtomicBoolean closed = new AtomicBoolean();

  public CompiledRuleBundle(KieContainer container, KieBase base) {
    this.container = container;
    this.base = base;
  }

  public KieContainer container() {
    return container;
  }

  public KieBase base() {
    return base;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) container.dispose();
  }
}
