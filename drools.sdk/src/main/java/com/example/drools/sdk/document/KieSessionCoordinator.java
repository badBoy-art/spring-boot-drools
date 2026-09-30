package com.example.drools.sdk.document;

/** Shared ownership registry used to route each session to exactly one live SDK node. */
public interface KieSessionCoordinator {
  /** Returns null if another non-expired node owns the session. */
  KieSessionLease acquire(String sessionId, String ownerId, long leaseMillis);

  boolean renew(KieSessionLease lease, long leaseMillis);

  boolean release(KieSessionLease lease);

  void deleteIfUnowned(String sessionId);

  /** Returns the currently leased owner, or null when the lease is absent/expired. */
  String ownerOf(String sessionId);
}
