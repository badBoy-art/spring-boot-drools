package com.example.drools.sdk.document;

import org.kie.api.runtime.KieSession;

/**
 * Rebinds application-owned runtime dependencies that are intentionally not part of KIE snapshots.
 */
public interface KieSessionLifecycle {
  void onCreate(String sessionId, String ruleType, KieSession session);

  void onRestore(String sessionId, String ruleType, KieSession session);
}
