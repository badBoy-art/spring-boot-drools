package com.example.drools.testsupport;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Owns only a randomly named schema in an explicitly selected test MySQL instance. */
public final class IsolatedTestDatabase implements AutoCloseable {
  private final DriverManagerDataSource dataSource;
  private JdbcTemplate admin;
  private String schema;

  public IsolatedTestDatabase() {
    String url = System.getProperty("drools.test.mysql.url");
    if (url == null)
      dataSource =
          new DriverManagerDataSource(
              "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    else {
      String username = System.getProperty("drools.test.mysql.username", "root");
      String password = System.getProperty("drools.test.mysql.password", "");
      admin = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
      schema = "drools_audit_" + UUID.randomUUID().toString().replace("-", "");
      admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
      String target = url.replaceFirst("(jdbc:mysql://[^/]+/)[^?]*", "$1" + schema);
      dataSource = new DriverManagerDataSource(target, username, password);
    }
  }

  public DriverManagerDataSource dataSource() {
    return dataSource;
  }

  @Override
  public void close() {
    if (admin != null) admin.execute("DROP DATABASE " + schema);
  }
}
