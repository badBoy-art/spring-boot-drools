package com.example.drools.sdk.document;

/** Persistent snapshot metadata and serialized native KieSession state. */
public final class StoredKieSession {
  private final String sessionId;
  private final String ruleType;
  private final String ruleFingerprint;
  private final String ruleBundle;
  private final byte[] payload;
  private final String ownerId;
  private final long fencingToken;

  public StoredKieSession(
      String sessionId,
      String ruleType,
      String ruleFingerprint,
      String ruleBundle,
      byte[] payload) {
    this(sessionId, ruleType, ruleFingerprint, ruleBundle, payload, null, 0L);
  }

  public StoredKieSession(
      String sessionId,
      String ruleType,
      String ruleFingerprint,
      String ruleBundle,
      byte[] payload,
      String ownerId,
      long fencingToken) {
    this.sessionId = sessionId;
    this.ruleType = ruleType;
    this.ruleFingerprint = ruleFingerprint;
    this.ruleBundle = ruleBundle;
    this.payload = payload;
    this.ownerId = ownerId;
    this.fencingToken = fencingToken;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getRuleType() {
    return ruleType;
  }

  public String getRuleFingerprint() {
    return ruleFingerprint;
  }

  public String getRuleBundle() {
    return ruleBundle;
  }

  public byte[] getPayload() {
    return payload;
  }

  public String getOwnerId() {
    return ownerId;
  }

  public long getFencingToken() {
    return fencingToken;
  }
}
