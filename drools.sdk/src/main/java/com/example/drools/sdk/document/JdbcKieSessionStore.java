package com.example.drools.sdk.document;

import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** JDBC snapshot store. Create the table with drools.sdk/src/main/resources/db/kie-session-store.sql. */
public final class JdbcKieSessionStore implements KieSessionStore {
  private final JdbcTemplate jdbc;

  public JdbcKieSessionStore(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public void save(final StoredKieSession session) {
    if (session.getOwnerId() != null) {
      String leasePredicate =
          "EXISTS (SELECT 1 FROM kie_session_lease l WHERE l.session_id=? AND l.owner_id=? AND"
              + " l.fencing_token=? AND l.lease_until>CURRENT_TIMESTAMP(6))";
      int updated =
          jdbc.update(
              "UPDATE kie_session_snapshot SET rule_type=?, rule_fingerprint=?, rule_bundle=?,"
                  + " session_payload=?, owner_id=?, fencing_token=?, update_time=CURRENT_TIMESTAMP"
                  + " WHERE session_id=? AND "
                  + leasePredicate,
              session.getRuleType(),
              session.getRuleFingerprint(),
              session.getRuleBundle(),
              session.getPayload(),
              session.getOwnerId(),
              session.getFencingToken(),
              session.getSessionId(),
              session.getSessionId(),
              session.getOwnerId(),
              session.getFencingToken());
      if (updated == 0) {
        updated =
            jdbc.update(
                "INSERT INTO kie_session_snapshot(session_id, rule_type, rule_fingerprint,"
                    + " rule_bundle, session_payload, owner_id, fencing_token) SELECT ?,?,?,?,?,?,?"
                    + " WHERE "
                    + leasePredicate,
                session.getSessionId(),
                session.getRuleType(),
                session.getRuleFingerprint(),
                session.getRuleBundle(),
                session.getPayload(),
                session.getOwnerId(),
                session.getFencingToken(),
                session.getSessionId(),
                session.getOwnerId(),
                session.getFencingToken());
      }
      if (updated != 1)
        throw new IllegalStateException(
            "KieSession lease was lost before snapshot write: " + session.getSessionId());
      return;
    }
    int updated =
        jdbc.update(
            "UPDATE kie_session_snapshot SET rule_type=?, rule_fingerprint=?, rule_bundle=?,"
                + " session_payload=?, owner_id=NULL, fencing_token=0,"
                + " update_time=CURRENT_TIMESTAMP WHERE session_id=?",
            session.getRuleType(),
            session.getRuleFingerprint(),
            session.getRuleBundle(),
            session.getPayload(),
            session.getSessionId());
    if (updated == 0)
      jdbc.update(
          "INSERT INTO kie_session_snapshot(session_id, rule_type, rule_fingerprint, rule_bundle,"
              + " session_payload, owner_id, fencing_token) VALUES(?,?,?,?,?,NULL,0)",
          session.getSessionId(),
          session.getRuleType(),
          session.getRuleFingerprint(),
          session.getRuleBundle(),
          session.getPayload());
  }

  @Override
  public StoredKieSession load(String sessionId) {
    try {
      return jdbc.queryForObject(
          "SELECT session_id, rule_type, rule_fingerprint, rule_bundle, session_payload, owner_id,"
              + " fencing_token FROM kie_session_snapshot WHERE session_id=?",
          new Object[] {sessionId},
          new RowMapper<StoredKieSession>() {
            @Override
            public StoredKieSession mapRow(ResultSet rs, int rowNum) throws SQLException {
              return new StoredKieSession(
                  rs.getString("session_id"),
                  rs.getString("rule_type"),
                  rs.getString("rule_fingerprint"),
                  rs.getString("rule_bundle"),
                  rs.getBytes("session_payload"),
                  rs.getString("owner_id"),
                  rs.getLong("fencing_token"));
            }
          });
    } catch (EmptyResultDataAccessException missing) {
      return null;
    }
  }

  @Override
  public void delete(String sessionId) {
    jdbc.update("DELETE FROM kie_session_snapshot WHERE session_id=?", sessionId);
  }

  @Override
  public void delete(String sessionId, KieSessionLease lease) {
    int deleted =
        jdbc.update(
            "DELETE FROM kie_session_snapshot WHERE session_id=? AND EXISTS (SELECT 1 FROM"
                + " kie_session_lease l WHERE l.session_id=? AND l.owner_id=? AND l.fencing_token=?"
                + " AND l.lease_until>CURRENT_TIMESTAMP(6))",
            sessionId,
            sessionId,
            lease.getOwnerId(),
            lease.getFencingToken());
    if (deleted == 0 && load(sessionId) != null)
      throw new IllegalStateException(
          "KieSession lease was lost before snapshot delete: " + sessionId);
  }
}
