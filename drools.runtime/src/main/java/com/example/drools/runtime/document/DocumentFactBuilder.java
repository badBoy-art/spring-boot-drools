package com.example.drools.runtime.document;

import com.example.drools.domain.DocFact;
import com.example.drools.domain.DocItem;
import com.example.drools.entity.RuleDocumentField;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单据事实构建器：把页面传来的单据报文 + 注册的"派生字段"表达式算成一个 {@link DocFact}。
 *
 * <p>为什么要有派生字段：业务口径里很多判定字段不是报文里的原始字段，而是算出来的 —— 例如商品审批要看"毛利率 = (售价 - 成本) / 售价"、订单要看"折扣率 = 优惠 / 总额"。
 * 运营在「① 单据注册」里登记字段时把表达式写上（如 {@code (price - cost) / price}）， 这里在单据进入规则引擎之前统一算好、放进 data，规则里直接 {@code
 * getNumber("毛利率")} 用。
 *
 * <p>好处：规则保持纯粹（只比大小/改状态），算法改口径只改一条字段配置，不用改规则、不用发版。
 */
public class DocumentFactBuilder {

  private static final Logger log = LoggerFactory.getLogger(DocumentFactBuilder.class);

  public DocFact build(String docCode, Map<String, Object> body, List<RuleDocumentField> fields) {
    Map<String, Object> data =
        body == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<String, Object>(body);
    DocFact fact = new DocFact(docCode, data);
    if (fact.getBizId() == null && body != null) {
      Object bizId = body.get("procInstId") != null ? body.get("procInstId") : body.get("bizId");
      fact.setBizId(bizId == null ? null : String.valueOf(bizId));
    }
    computeDerivedFields(fact, fields);
    return fact;
  }

  /**
   * 把单据里的数组对象（is_collection=1）逐条拆成 {@link DocItem} 行事实。 评估入口把它们与主事实 DocFact 一起插入会话，规则即可做「明细行第 N 条 /
   * 每条」校验。
   */
  @SuppressWarnings("unchecked")
  public List<DocItem> buildItems(
      String docCode, DocFact fact, List<com.example.drools.entity.RuleDocumentObject> objects) {
    List<DocItem> items = new ArrayList<DocItem>();
    for (com.example.drools.entity.RuleDocumentObject o : objects) {
      if (o.getIsCollection() == null || o.getIsCollection() != 1) {
        continue;
      }
      String collectionPath =
          o.getValuePath() == null || o.getValuePath().trim().isEmpty()
              ? o.getObjectKey()
              : o.getValuePath();
      Object raw = fact.get(collectionPath);
      if (!(raw instanceof List)) {
        continue;
      }
      List<?> list = (List<?>) raw;
      List<DocItem> pathItems = new ArrayList<DocItem>();
      for (int i = 0; i < list.size(); i++) {
        Object elem = list.get(i);
        if (elem instanceof Map) {
          DocItem item =
              new DocItem(
                  docCode,
                  o.getObjectKey(),
                  i,
                  new LinkedHashMap<String, Object>((Map<String, Object>) elem));
          items.add(item);
          pathItems.add(item);
        }
      }
      fact.getLineItemsByPath().put(collectionPath, pathItems);
    }
    return items;
  }

  /**
   * 按注册的表达式计算派生字段，支持多个字段参与计算（如 毛利率 = (销售价格 - 成本价) / 成本价）。
   *
   * <p>三个要点（都是实测需要才加的）： 1) 字段既能按 fieldKey 引用，也能按**中文名**引用：这里给每个字段建立中文名别名一起塞进上下文 2)
   * 派生字段可以引用另一个派生字段（如 折扣后价 = 售价 * (1 - 折扣率)），按依赖拓扑顺序算，不靠注册顺序 3) 环依赖 /
   * 缺字段只记消息、不中断（缺字段会让规则不命中，比抛异常更安全）
   */
  public void computeDerivedFields(DocFact fact, List<RuleDocumentField> fields) {
    if (fields == null || fields.isEmpty()) {
      return;
    }
    Map<String, Object> data = fact.getData();
    // 中文名 <-> fieldKey 双向别名
    for (RuleDocumentField f : fields) {
      alias(data, f.getFieldName(), f.getFieldKey());
    }

    List<RuleDocumentField> derived = new ArrayList<RuleDocumentField>();
    for (RuleDocumentField f : fields) {
      if (f.getExpr() != null && !f.getExpr().trim().isEmpty()) {
        derived.add(f);
      }
    }
    if (derived.isEmpty()) {
      return;
    }

    List<String> failures = new ArrayList<String>();
    Set<String> computed = new LinkedHashSet<String>();
    // 拓扑排序：算完上游再算下游（引用关系从表达式里扫 fieldKey / 中文名）
    List<RuleDocumentField> pending = new ArrayList<RuleDocumentField>(derived);
    int guard = 0;
    while (!pending.isEmpty() && guard++ <= derived.size() * derived.size() + derived.size()) {
      boolean progressed = false;
      for (Iterator<RuleDocumentField> it = pending.iterator(); it.hasNext(); ) {
        RuleDocumentField f = it.next();
        Set<String> deps = dependencies(f, derived, fields);
        boolean ready = true;
        for (String dep : deps) {
          if (!computed.contains(dep)) {
            ready = false;
            break;
          }
        }
        if (!ready) {
          continue;
        }
        try {
          Object value = MvelExpr.eval(f.getExpr().trim(), data);
          if (value != null) {
            data.put(f.getFieldKey(), value);
            alias(data, f.getFieldName(), f.getFieldKey());
            log.debug(
                "派生字段 {}.{}({}) = {}", fact.getDocCode(), f.getFieldKey(), f.getExpr(), value);
          }
          computed.add(f.getFieldKey());
        } catch (Exception e) {
          failures.add(describeFailure(f, e));
          computed.add(f.getFieldKey()); // 算不出来也要放行，避免依赖它的字段被永久卡住
        }
        it.remove();
        progressed = true;
      }
      if (!progressed) {
        StringBuilder cyc = new StringBuilder();
        for (RuleDocumentField f : pending) {
          cyc.append(f.getFieldKey()).append(" ");
        }
        failures.add("派生字段存在循环依赖，无法排序：" + cyc.toString().trim());
        break;
      }
    }
    for (String failure : failures) {
      fact.addRuleMessage("派生字段计算失败：" + failure);
      log.warn("派生字段计算失败 docCode={} {}", fact.getDocCode(), failure);
    }
  }

  /** 中文名别名：把中文名当上下文键，表达式里用中文名也能算（key 为空则跳过） */
  private void alias(Map<String, Object> data, String fieldName, String fieldKey) {
    if (fieldName == null
        || fieldName.trim().isEmpty()
        || fieldKey == null
        || fieldKey.trim().isEmpty()) {
      return;
    }
    String name = fieldName.trim();
    if (name.equals(fieldKey)) {
      return;
    }
    if (data.containsKey(fieldKey) && !data.containsKey(name)) {
      data.put(name, data.get(fieldKey));
    } else if (data.containsKey(name) && !data.containsKey(fieldKey)) {
      data.put(fieldKey, data.get(name));
    }
  }

  /** 表达式依赖了哪些派生字段（按 fieldKey 或中文名扫描） */
  private Set<String> dependencies(
      RuleDocumentField field, List<RuleDocumentField> derived, List<RuleDocumentField> all) {
    String expr = field.getExpr();
    Set<String> deps = new LinkedHashSet<String>();
    for (RuleDocumentField other : all) {
      if (other == field || other.getFieldKey() == null) {
        continue;
      }
      boolean isDerived = false;
      for (RuleDocumentField d : derived) {
        if (d == other) {
          isDerived = true;
          break;
        }
      }
      if (!isDerived) {
        continue;
      }
      if (containsIdentifier(expr, other.getFieldKey())
          || (other.getFieldName() != null && containsIdentifier(expr, other.getFieldName()))) {
        deps.add(other.getFieldKey());
      }
    }
    return deps;
  }

  private boolean containsIdentifier(String expr, String token) {
    if (expr == null || token == null || token.trim().isEmpty()) {
      return false;
    }
    return java.util.regex.Pattern.compile(
            "(?<![\\w$])" + java.util.regex.Pattern.quote(token.trim()) + "(?![\\w$])")
        .matcher(expr)
        .find();
  }

  /** 失败原因里带上"引用了哪个没传的字段"，比 MVEL 原文更好定位 */
  private String describeFailure(RuleDocumentField f, Exception e) {
    String msg = e.getMessage() == null ? e.toString() : e.getMessage();
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile(
                "(?:unresolvable property or identifier|unknown property|Unable to"
                    + " resolve)[:\\s]*([\\w\\u4e00-\\u9fa5.]+)")
            .matcher(msg);
    String hint = m.find() ? "（引用的字段没传或未注册：" + m.group(1) + "）" : "";
    return f.getFieldName() + "[" + f.getFieldKey() + "] = " + f.getExpr() + " → " + msg + hint;
  }
}
