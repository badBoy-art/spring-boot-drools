package com.example.drools.sdk.document;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.kie.api.runtime.KieSession;

/** Owns one stateful KieSession; keep this handle between business HTTP requests. */
public final class ManagedKieSession implements AutoCloseable {
  private final KieSession session;
  private final Runnable onClose;
  private final ExecutorService fireExecutor;
  private final AtomicBoolean firing = new AtomicBoolean(false);
  private volatile Future<?> fireTask;
  private volatile Throwable fireFailure;
  private volatile boolean closed;

  ManagedKieSession(KieSession session, String sessionId, Runnable onClose) {
    this.session = session;
    this.onClose = onClose;
    this.fireExecutor =
        Executors.newSingleThreadExecutor(
            new ThreadFactory() {
              @Override
              public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "drools-fireUntilHalt-" + sessionId);
                thread.setDaemon(true);
                return thread;
              }
            });
  }

  /** Exposes native KIE APIs without a platform wrapper that would restrict Drools features. */
  public KieSession kieSession() {
    ensureOpen();
    return session;
  }

  /** Starts active mode on a dedicated daemon thread. Stop by calling halt() or close(). */
  public synchronized void startFireUntilHalt() {
    ensureOpen();
    if (!firing.compareAndSet(false, true)) return;
    fireTask =
        fireExecutor.submit(
            new Runnable() {
              @Override
              public void run() {
                try {
                  session.fireUntilHalt();
                } catch (Throwable failure) {
                  fireFailure = failure;
                } finally {
                  firing.set(false);
                }
              }
            });
  }

  public synchronized void halt() {
    if (closed || !firing.get()) return;
    session.halt();
    Future<?> task = fireTask;
    if (task != null) {
      try {
        task.get(5, TimeUnit.SECONDS);
      } catch (TimeoutException e) {
        task.cancel(true);
        throw new IllegalStateException("fireUntilHalt did not stop within the timeout", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while stopping fireUntilHalt", e);
      } catch (java.util.concurrent.ExecutionException e) {
        throw new IllegalStateException("fireUntilHalt failed", e.getCause());
      }
    }
    if (fireFailure != null) throw new IllegalStateException("fireUntilHalt failed", fireFailure);
  }

  public Throwable fireFailure() {
    return fireFailure;
  }

  public boolean isFiring() {
    return firing.get();
  }

  private void ensureOpen() {
    if (closed) throw new IllegalStateException("KieSession is closed");
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    try {
      halt();
    } finally {
      closed = true;
      fireExecutor.shutdownNow();
      session.dispose();
      if (onClose != null) onClose.run();
    }
  }
}
