package com.example.drools.worker;

import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkerStateRepository {
  private final JdbcTemplate jdbc;

  public WorkerStateRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void heartbeat(String nodeId, String baseUrl) {
    jdbc.update(
        "INSERT INTO kie_worker_node(node_id, base_url, heartbeat_time)"
            + " VALUES(?,?,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE base_url=VALUES(base_url),"
            + " heartbeat_time=CURRENT_TIMESTAMP",
        nodeId,
        baseUrl);
  }

  public String nodeUrl(String nodeId) {
    Timestamp cutoff = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
    cutoff = new Timestamp(cutoff.getTime() - 60000L);
    List<String> urls =
        jdbc.query(
            "SELECT base_url FROM kie_worker_node WHERE node_id=? AND heartbeat_time > ?",
            new Object[] {nodeId, cutoff},
            (rs, row) -> rs.getString(1));
    return urls.isEmpty() ? null : urls.get(0);
  }

  public void registerSession(
      String sessionId, String ruleType, String clockType, boolean active, boolean timedAuto) {
    jdbc.update(
        "INSERT INTO kie_worker_session(session_id, rule_type, clock_type, active_mode, timed_auto,"
            + " status) VALUES(?,?,?,?,?, 'OPEN')",
        sessionId,
        ruleType,
        clockType,
        active ? 1 : 0,
        timedAuto ? 1 : 0);
  }

  public List<WorkerSessionRecord> openSessions() {
    return jdbc.query(
        "SELECT session_id, rule_type, clock_type, active_mode, timed_auto FROM kie_worker_session"
            + " WHERE status='OPEN'",
        (rs, row) ->
            new WorkerSessionRecord(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getBoolean(4),
                rs.getBoolean(5)));
  }

  public WorkerSessionRecord findSession(String sessionId) {
    List<WorkerSessionRecord> rows =
        jdbc.query(
            "SELECT session_id, rule_type, clock_type, active_mode, timed_auto FROM"
                + " kie_worker_session WHERE session_id=? AND status='OPEN'",
            new Object[] {sessionId},
            (rs, row) ->
                new WorkerSessionRecord(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getBoolean(4),
                    rs.getBoolean(5)));
    return rows.isEmpty() ? null : rows.get(0);
  }

  public void closeSession(String sessionId) {
    jdbc.update(
        "UPDATE kie_worker_session SET status='CLOSED', update_time=CURRENT_TIMESTAMP WHERE"
            + " session_id=?",
        sessionId);
  }

  public void setActive(String sessionId, boolean active) {
    jdbc.update(
        "UPDATE kie_worker_session SET active_mode=?, update_time=CURRENT_TIMESTAMP WHERE"
            + " session_id=? AND status='OPEN'",
        active ? 1 : 0,
        sessionId);
  }

  public <T> T transaction(java.util.function.Supplier<T> action) {
    org.springframework.transaction.support.TransactionTemplate tx =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                jdbc.getDataSource()));
    return tx.execute(status -> action.get());
  }

  public java.util.Map<String, Object> command(String id, String key) {
    java.util.List<java.util.Map<String, Object>> found =
        jdbc.queryForList(
            "SELECT request_hash,response_json FROM kie_worker_command WHERE session_id=? AND"
                + " command_id=?",
            id,
            key);
    return found.isEmpty() ? null : found.get(0);
  }

  public void saveCommand(String id, String key, String hash, String response) {
    jdbc.update(
        "INSERT INTO kie_worker_command(session_id,command_id,request_hash,response_json)"
            + " VALUES(?,?,?,?)",
        id,
        key,
        hash,
        response);
  }

  public void saveGlobal(String id, String name, String value) {
    int updated =
        jdbc.update(
            "UPDATE kie_worker_global SET value_json=? WHERE session_id=? AND global_name=?",
            value,
            id,
            name);
    if (updated == 0)
      jdbc.update(
          "INSERT INTO kie_worker_global(session_id,global_name,value_json) VALUES(?,?,?)",
          id,
          name,
          value);
  }

  public java.util.Map<String, String> globals(String id) {
    java.util.Map<String, String> values = new java.util.LinkedHashMap<String, String>();
    jdbc.query(
        "SELECT global_name,value_json FROM kie_worker_global WHERE session_id=?",
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> values.put(rs.getString(1), rs.getString(2)),
        id);
    return values;
  }

  public static final class WorkerSessionRecord {
    public final String sessionId;
    public final String ruleType;
    public final String clockType;
    public final boolean active;
    public final boolean timedAuto;

    WorkerSessionRecord(
        String sessionId, String ruleType, String clockType, boolean active, boolean timedAuto) {
      this.sessionId = sessionId;
      this.ruleType = ruleType;
      this.clockType = clockType;
      this.active = active;
      this.timedAuto = timedAuto;
    }
  }
}
