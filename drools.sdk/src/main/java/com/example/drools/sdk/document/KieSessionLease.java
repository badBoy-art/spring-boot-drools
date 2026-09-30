package com.example.drools.sdk.document;

/** Fenced exclusive ownership of one persistent session. */
public final class KieSessionLease {
  private final String sessionId;
  private final String ownerId;
  private final long fencingToken;

  public KieSessionLease(String sessionId, String ownerId, long fencingToken) {
    this.sessionId = sessionId;
    this.ownerId = ownerId;
    this.fencingToken = fencingToken;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getOwnerId() {
    return ownerId;
  }

  public long getFencingToken() {
    return fencingToken;
  }
}
