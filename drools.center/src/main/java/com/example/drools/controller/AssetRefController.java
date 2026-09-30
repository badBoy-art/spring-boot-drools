package com.example.drools.controller;

import com.example.drools.dao.AssetRefDao;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资产引用 & 护栏： - 单据（①）是独立资产：注册一次，被多个规则类型/多条规则复用； - 页面靠 /rule/refs 把"被谁引用"显示出来（一处注册、多处调用要看得见）； -
 * 删除被引用的单据会被拦住并告知引用者，避免悄悄打断别处的调用链。
 */
@RestController
@RequestMapping("/rule")
public class AssetRefController {

  private final AssetRefDao refDao;
  private final JdbcTemplate jdbc;

  public AssetRefController(AssetRefDao refDao, JdbcTemplate jdbc) {
    this.refDao = refDao;
    this.jdbc = jdbc;
  }

  /** 全量引用关系，页面一次拉取渲染"被引用"徽标 */
  @GetMapping("/refs")
  public Map<String, Object> refs() {
    return refDao.refs();
  }

  /** 某个接口的引用清单（适用范围 / 被哪些单据 / 哪些规则引用） */
  @GetMapping("/refs/doc/{docCode}")
  public ResponseEntity<?> docRefs(@PathVariable String docCode) {
    Map<String, Object> item = refDao.docRefs(docCode);
    if (item == null) return ResponseEntity.badRequest().body(err("单据未注册: " + docCode));
    return ResponseEntity.ok(item);
  }

  /** 删除单据：被引用则拒绝（409）；未被引用时连带删掉它的对象/字段 */
  @DeleteMapping("/doc/{docCode}")
  public ResponseEntity<?> deleteDoc(@PathVariable String docCode) {
    Map<String, Object> ref = refDao.docRefs(docCode);
    if (ref == null) return ResponseEntity.badRequest().body(err("单据未注册: " + docCode));
    String blockers = describe(ref);
    if (!blockers.isEmpty()) {
      return ResponseEntity.status(409)
          .body(err("单据 " + docCode + " 正被引用，不能删除：" + blockers + "（先处理掉这些引用，再删单据）"));
    }
    jdbc.update("DELETE FROM rule_document_field WHERE doc_code = ?", docCode);
    jdbc.update("DELETE FROM rule_document_object WHERE doc_code = ?", docCode);
    jdbc.update("DELETE FROM rule_document WHERE doc_code = ?", docCode);
    return ResponseEntity.ok(ok("单据 " + docCode + " 已删除（未被任何规则引用）"));
  }

  /** 把引用清单拼成中文句子，例如 "2 个规则类型[SKU_MARGIN_L2]、3 条规则[...]" */
  private String describe(Map<String, Object> ref) {
    String[] labels = {"types", "rules"};
    String[] names = {"规则类型", "规则"};
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < labels.length; i++) {
      Object v = ref.get(labels[i]);
      if (!(v instanceof List) || ((List<?>) v).isEmpty()) continue;
      List<?> list = (List<?>) v;
      if (sb.length() > 0) sb.append("；");
      sb.append(list.size()).append(" 个").append(names[i]).append(list);
    }
    return sb.toString();
  }

  private Map<String, Object> err(String msg) {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    m.put("error", "参数/规则校验失败");
    m.put("message", msg);
    return m;
  }

  private Map<String, Object> ok(String msg) {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    m.put("success", true);
    m.put("message", msg);
    return m;
  }
}
