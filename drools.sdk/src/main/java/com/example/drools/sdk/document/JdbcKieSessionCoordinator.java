package com.example.drools.sdk.document;

import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** MySQL-compatible lease registry with monotonically increasing fencing tokens. */
public final class JdbcKieSessionCoordinator implements KieSessionCoordinator {
  private final JdbcTemplate jdbc;

  public JdbcKieSessionCoordinator(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public KieSessionLease acquire(String sessionId, String ownerId, long leaseMillis) {
    validate(sessionId, ownerId, leaseMillis);
    long micros = Math.multiplyExact(leaseMillis, 1000L);
    try {
      jdbc.update(
          "INSERT INTO kie_session_lease(session_id, owner_id, lease_until, fencing_token)"
              + " VALUES(?,?,TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)),1)",
          sessionId,
          ownerId,
          micros);
    } catch (DuplicateKeyException alreadyRegistered) {
      int acquired =
          jdbc.update(
              "UPDATE kie_session_lease SET owner_id=?, lease_until=TIMESTAMPADD(MICROSECOND, ?,"
                  + " CURRENT_TIMESTAMP(6)), fencing_token=fencing_token+1,"
                  + " update_time=CURRENT_TIMESTAMP(6) WHERE session_id=? AND"
                  + " lease_until<=CURRENT_TIMESTAMP(6)",
              ownerId,
              micros,
              sessionId);
      if (acquired == 0) return null;
    }
    java.util.List<Long> tokens =
        jdbc.query(
            "SELECT fencing_token FROM kie_session_lease WHERE session_id=? AND owner_id=? AND"
                + " lease_until>CURRENT_TIMESTAMP(6)",
            new Object[] {sessionId, ownerId},
            (rs, rowNum) -> rs.getLong(1));
    return tokens.isEmpty() ? null : new KieSessionLease(sessionId, ownerId, tokens.get(0));
  }

  @Override
  public boolean renew(KieSessionLease lease, long leaseMillis) {
    if (lease == null || leaseMillis <= 0) return false;
    int changed =
        jdbc.update(
            "UPDATE kie_session_lease SET lease_until=TIMESTAMPADD(MICROSECOND, ?,"
                + " CURRENT_TIMESTAMP(6)), update_time=CURRENT_TIMESTAMP(6) WHERE session_id=? AND"
                + " owner_id=? AND fencing_token=? AND lease_until>CURRENT_TIMESTAMP(6)",
            Math.multiplyExact(leaseMillis, 1000L),
            lease.getSessionId(),
            lease.getOwnerId(),
            lease.getFencingToken());
    return changed == 1;
  }

  @Override
  public boolean release(KieSessionLease lease) {
    if (lease == null) return false;
    return jdbc.update(
            "UPDATE kie_session_lease SET owner_id=NULL, lease_until=CURRENT_TIMESTAMP(6),"
                + " update_time=CURRENT_TIMESTAMP(6) WHERE session_id=? AND owner_id=? AND"
                + " fencing_token=?",
            lease.getSessionId(),
            lease.getOwnerId(),
            lease.getFencingToken())
        == 1;
  }

  @Override
  public String ownerOf(String sessionId) {
    java.util.List<String> owners =
        jdbc.query(
            "SELECT owner_id FROM kie_session_lease WHERE session_id=? AND owner_id IS NOT NULL AND"
                + " lease_until>CURRENT_TIMESTAMP(6)",
            new Object[] {sessionId},
            (rs, rowNum) -> rs.getString(1));
    return owners.isEmpty() ? null : owners.get(0);
  }

  @Override
  public void deleteIfUnowned(String sessionId) {
    jdbc.update(
        "DELETE FROM kie_session_lease WHERE session_id=? AND (owner_id IS NULL OR"
            + " lease_until<=CURRENT_TIMESTAMP(6))",
        sessionId);
  }

  private void validate(String sessionId, String ownerId, long leaseMillis) {
    if (sessionId == null
        || sessionId.trim().isEmpty()
        || ownerId == null
        || ownerId.trim().isEmpty()
        || leaseMillis <= 0)
      throw new IllegalArgumentException(
          "sessionId, ownerId and a positive lease duration are required");
  }
}
