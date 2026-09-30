package com.example.drools.sdk.document;

import org.kie.api.KieBase;
import org.kie.api.runtime.KieContainer;

/**
 * Retains a native generation while the application owns stateless/pool/named sessions or scanners.
 */
public final class NativeRuntimeLease implements AutoCloseable {
  private final KieContainer container;
  private final KieBase base;
  private final Runnable release;
  private boolean closed;

  NativeRuntimeLease(KieContainer container, KieBase base, Runnable release) {
    this.container = container;
    this.base = base;
    this.release = release;
  }

  public KieContainer container() {
    return container;
  }

  public KieBase base() {
    return base;
  }

  public synchronized void close() {
    if (!closed) {
      closed = true;
      release.run();
    }
  }
}
