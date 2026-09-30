package com.example.drools.sdk.document;

/**
 * Optional hook for Micrometer/logging integrations without forcing a metrics library on SDK users.
 */
public interface KieSessionSnapshotObserver {
  default void onCheckpoint(String sessionId, long payloadBytes, long elapsedNanos) {}

  default void onRestore(String sessionId, long payloadBytes, long elapsedNanos) {}

  default void onLeaseLost(String sessionId, String nodeId) {}
}
