package com.example.drools;

import static org.junit.jupiter.api.Assertions.*;

import com.example.drools.dao.RuleDefinitionDao;
import com.example.drools.entity.RuleDefinition;
import com.example.drools.testsupport.IsolatedTestDatabase;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;

class RuleDefinitionDaoTest {
  @Test
  void insertUsesDatabaseTimestampDefaultsFromTheDeploymentSchema() throws Exception {
    try (IsolatedTestDatabase database = new IsolatedTestDatabase()) {
      JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
      try (InputStream input = new ClassPathResource("db/rule-type.sql").getInputStream()) {
        String ddl = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        Matcher table = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS rule_definition\\s*\\(.*?\\) ENGINE=.*?;",
            Pattern.DOTALL).matcher(ddl);
        assertTrue(table.find(), "Deployment DDL must define rule_definition");
        jdbc.execute(table.group());
      }
      RuleDefinitionDao dao = new RuleDefinitionDao(jdbc);
      RuleDefinition draft = new RuleDefinition();
      draft.setRuleGroup("PRODUCT");
      draft.setRuleType("PRODUCT_MULTI_APPROVAL");
      draft.setRuleName("timestamp_default_regression");
      draft.setRuleParams("{}");
      draft.setStatus(0);
      draft.setVersion(0);
      dao.insert(draft);
      RuleDefinition saved = dao.findById(draft.getId());
      assertNotNull(saved.getCreateTime());
      assertNotNull(saved.getUpdateTime());
      assertEquals("PRODUCT_MULTI_APPROVAL", saved.getRuleType());
    }
  }
}
