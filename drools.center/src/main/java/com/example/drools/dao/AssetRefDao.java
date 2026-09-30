package com.example.drools.dao;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 单据引用反查，依据 rule_type_meta.doc_code 的注册绑定关系。 */
@Repository
public class AssetRefDao {

  private final JdbcTemplate jdbc;

  public AssetRefDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 全量引用关系：{"docs":{docCode:{types,rules,refCount}}} */
  public Map<String, Object> refs() {
    List<Map<String, Object>> docs =
        jdbc.queryForList("SELECT doc_code, doc_name FROM rule_document ORDER BY doc_code");
    List<Map<String, Object>> types =
        jdbc.queryForList("SELECT rule_type, doc_code FROM rule_type_meta");
    List<Map<String, Object>> rules =
        jdbc.queryForList("SELECT rule_name, rule_type FROM rule_definition");

    // ---------- 每个单据：被谁引用 ----------
    Map<String, Object> docOut = new LinkedHashMap<String, Object>();
    for (Map<String, Object> d : docs) {
      String doc = str(d.get("doc_code"));
      Set<String> usedTypes = new LinkedHashSet<String>();
      Set<String> usedRules = new LinkedHashSet<String>();

      for (Map<String, Object> t : types) {
        if (doc.equals(str(t.get("doc_code")))) usedTypes.add(str(t.get("rule_type")));
      }
      for (Map<String, Object> r : rules) {
        if (usedTypes.contains(str(r.get("rule_type")))) usedRules.add(str(r.get("rule_name")));
      }

      Map<String, Object> item = new LinkedHashMap<String, Object>();
      item.put("docCode", doc);
      item.put("docName", str(d.get("doc_name")));
      item.put("types", new ArrayList<String>(usedTypes));
      item.put("rules", new ArrayList<String>(usedRules));
      item.put("refCount", usedTypes.size() + usedRules.size());
      docOut.put(doc, item);
    }

    Map<String, Object> out = new LinkedHashMap<String, Object>();
    out.put("docs", docOut);
    return out;
  }

  /** 删除护栏用：某单据的引用者清单（null=单据不存在） */
  @SuppressWarnings("unchecked")
  public Map<String, Object> docRefs(String docCode) {
    Object item = ((Map<String, Object>) refs().get("docs")).get(docCode);
    return item instanceof Map ? (Map<String, Object>) item : null;
  }

  private static String str(Object o) {
    return o == null ? "" : String.valueOf(o);
  }
}
