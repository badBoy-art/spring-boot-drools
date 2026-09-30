package com.example.drools.sdk.document;

/** Storage SPI so applications can use JDBC or their own transactional store. */
public interface KieSessionStore {
  void save(StoredKieSession session);

  StoredKieSession load(String sessionId);

  void delete(String sessionId);

  default void delete(String sessionId, KieSessionLease lease) {
    delete(sessionId);
  }
}
