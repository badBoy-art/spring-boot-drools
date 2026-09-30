package com.example.drools.runtime.document;

import com.example.drools.domain.DocFact;
import com.example.drools.domain.DocItem;
import com.example.drools.entity.RuleOutputField;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 返回值结构组装：把规则类型上注册的「返回结果结构」树（rule_output_field）组装成决策 decision。
 *
 * <p>节点语义： OBJECT —— 容器（子节点挂 parent_path），产出嵌套 Map； ARRAY —— 数组，array_from 指定从 ext/data 的哪个 List
 * 取整段；若其下挂了叶子（item 模板），则逐元素映射； LEAF —— 叶子，source 决定取值：EXT(规则写入 ext) / DATA(单据原值或派生值) / EXPR(MVEL
 * 算式，引擎算好) / CONST(字面量)。
 *
 * <p>这就是「返回结构里字段经规则引擎计算赋值」的落地：LEAF 可指向规则写进 ext 的决策值（EXT）， 也可直接写多字段算式由引擎计算（EXPR）。
 */
public class DocumentOutputAssembler {

  public Map<String, Object> assemble(DocFact fact, List<RuleOutputField> nodes) {
    Map<String, Object> out = new LinkedHashMap<String, Object>();
    if (nodes != null && !nodes.isEmpty()) build(out, fact, null, nodes);
    return out;
  }

  private void build(
      Map<String, Object> container, DocFact fact, String parentPath, List<RuleOutputField> all) {
    List<RuleOutputField> children = new ArrayList<RuleOutputField>();
    for (RuleOutputField n : all) {
      if (eq(n.getParentPath(), parentPath)) {
        children.add(n);
      }
    }
    children.sort(Comparator.comparing(n -> n.getSortOrder() == null ? 1 : n.getSortOrder()));
    for (RuleOutputField n : children) {
      String key = leafKey(n.getOutputPath());
      Object v = resolve(fact, n, all);
      if (v != null) {
        container.put(key, v);
      }
    }
  }

  private Object resolve(DocFact fact, RuleOutputField node, List<RuleOutputField> all) {
    String kind = node.getNodeKind() == null ? "LEAF" : node.getNodeKind();
    if ("OBJECT".equals(kind)) {
      Map<String, Object> m = new LinkedHashMap<String, Object>();
      build(m, fact, node.getOutputPath(), all);
      return m.isEmpty() ? null : m;
    }
    if ("ARRAY".equals(kind)) {
      List<?> list = resolveArraySource(fact, node.getArrayFrom());
      if (list == null) {
        return null;
      }
      List<RuleOutputField> itemNodes = new ArrayList<RuleOutputField>();
      for (RuleOutputField n : all) {
        if (eq(n.getParentPath(), node.getOutputPath())) {
          itemNodes.add(n);
        }
      }
      if (itemNodes.isEmpty()) {
        return list; // 整段列表原样返回
      }
      List<Object> mapped = new ArrayList<Object>();
      String itemPath = stripSourcePrefix(node.getArrayFrom());
      List<DocItem> lineItems = fact.getLineItemsByPath().get(itemPath);
      for (int index = 0; index < list.size(); index++) {
        Object elem = list.get(index);
        Map<String, Object> elementData = elementData(elem);
        if (elementData != null) {
          Map<String, Object> em =
              mapElement(elementData, node.getOutputPath(), all, findLineItem(lineItems, index));
          if (em != null) {
            mapped.add(em);
          }
        } else {
          mapped.add(elem);
        }
      }
      return mapped;
    }
    return resolveLeaf(fact, node);
  }

  /** 元素级递归映射：数组元素也支持 OBJECT、ARRAY 和任意层级的 LEAF。 */
  private Map<String, Object> mapElement(
      Map<String, Object> elem, String parentPath, List<RuleOutputField> all, DocItem lineItem) {
    Map<String, Object> out = new LinkedHashMap<String, Object>();
    List<RuleOutputField> itemNodes = children(parentPath, all);
    for (RuleOutputField n : itemNodes) {
      String key = leafKey(n.getOutputPath());
      Object v;
      if ("OBJECT".equals(n.getNodeKind())) {
        Map<String, Object> nested = mapElement(elem, n.getOutputPath(), all, lineItem);
        v = nested == null || nested.isEmpty() ? null : nested;
      } else if ("ARRAY".equals(n.getNodeKind())) {
        Object raw = getPath(elem, stripSourcePrefix(n.getArrayFrom()));
        if (!(raw instanceof List)) {
          v = null;
        } else {
          List<Object> nested = new ArrayList<Object>();
          for (Object child : (List<?>) raw) {
            Map<String, Object> childData = elementData(child);
            if (childData != null) nested.add(mapElement(childData, n.getOutputPath(), all, null));
            else nested.add(child);
          }
          v = nested;
        }
      } else {
        v = resolveLeafAgainst(elem, n, lineItem);
      }
      if (v != null) {
        out.put(key, v);
      }
    }
    return out.isEmpty() ? null : out;
  }

  private List<RuleOutputField> children(String parentPath, List<RuleOutputField> all) {
    List<RuleOutputField> children = new ArrayList<RuleOutputField>();
    for (RuleOutputField n : all) if (eq(n.getParentPath(), parentPath)) children.add(n);
    children.sort(Comparator.comparing(n -> n.getSortOrder() == null ? 1 : n.getSortOrder()));
    return children;
  }

  private DocItem findLineItem(List<DocItem> items, int index) {
    if (items == null) return null;
    for (DocItem item : items) if (item.getIndex() == index) return item;
    return null;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> elementData(Object element) {
    if (element instanceof Map) return (Map<String, Object>) element;
    if (element instanceof DocItem) return ((DocItem) element).getData();
    return null;
  }

  private String stripSourcePrefix(String path) {
    if (path == null) return null;
    if (path.startsWith("data.")) return path.substring(5);
    if (path.startsWith("ext.")) return path.substring(4);
    return path;
  }

  private Object resolveLeaf(DocFact fact, RuleOutputField node) {
    String source = node.getSource() == null ? "DATA" : node.getSource();
    String val = node.getSourceValue();
    Object value;
    if ("EXT".equals(source)) {
      value = fact.getExt().get(val);
    } else if ("DATA".equals(source)) {
      value = fact.get(val); // 支持嵌套路径（a.b / items[0].x）
    } else if ("CONST".equals(source)) {
      value = val;
    } else if ("EXPR".equals(source)) {
      value = evalExpr(fact, val);
    } else {
      return null;
    }
    return coerce(value, node.getValueType());
  }

  private Object resolveLeafAgainst(
      Map<String, Object> elem, RuleOutputField node, DocItem lineItem) {
    String source = node.getSource() == null ? "DATA" : node.getSource();
    String val = node.getSourceValue();
    Object value;
    if ("EXT".equals(source) && lineItem != null) {
      value = lineItem.getExt().get(val);
    } else if ("DATA".equals(source)) {
      value = getPath(elem, val);
    } else if ("CONST".equals(source)) {
      value = val;
    } else if ("EXPR".equals(source)) {
      Map<String, Object> ctx = new LinkedHashMap<String, Object>(elem);
      if (lineItem != null) {
        ctx.putAll(lineItem.getExt());
        ctx.put("ext", lineItem.getExt());
      }
      value = safeExpr(val, ctx);
    } else {
      return null;
    }
    return coerce(value, node.getValueType());
  }

  /** EXPR 算式上下文：单据 data 打平 + ext 打平（ext 优先，方便引用规则写进去的值） */
  private Object evalExpr(DocFact fact, String expr) {
    Map<String, Object> ctx = new LinkedHashMap<String, Object>(fact.getData());
    for (Map.Entry<String, Object> e : fact.getExt().entrySet()) {
      if (e.getValue() != null) {
        ctx.put(e.getKey(), e.getValue());
      }
    }
    ctx.put("ext", fact.getExt());
    return safeExpr(expr, ctx);
  }

  /** EXPR 求值：引用的字段没传/没算出来时返回 null（该输出位被跳过），不打断整个决策组装 */
  private Object safeExpr(String expr, Map<String, Object> ctx) {
    try {
      return MvelExpr.eval(expr, ctx);
    } catch (Exception e) {
      return null;
    }
  }

  private List<?> resolveArraySource(DocFact fact, String arrayFrom) {
    if (arrayFrom == null || arrayFrom.trim().isEmpty()) {
      return null;
    }
    String s = arrayFrom.trim();
    Object v;
    if (s.startsWith("ext.")) {
      v = fact.getExt().get(s.substring(4));
    } else if (s.startsWith("data.")) {
      v = fact.get(s.substring(5));
    } else {
      v = fact.get(s);
    }
    return v instanceof List ? (List<?>) v : null;
  }

  private Object coerce(Object rawValue, String valueType) {
    String t = valueType == null ? "STRING" : valueType.toUpperCase();
    if (rawValue == null) {
      return null;
    }
    String raw = String.valueOf(rawValue);
    try {
      if ("NUMBER".equals(t) || "DOUBLE".equals(t)) {
        return Double.valueOf(raw);
      }
      if ("INT".equals(t) || "LONG".equals(t)) {
        return Long.valueOf(raw.trim());
      }
      if ("DECIMAL".equals(t)) {
        return new BigDecimal(raw.trim());
      }
      if ("BOOLEAN".equals(t)) {
        if (rawValue instanceof Boolean) return rawValue;
        if ("true".equalsIgnoreCase(raw) || "1".equals(raw) || "是".equals(raw)) return true;
        if ("false".equalsIgnoreCase(raw) || "0".equals(raw) || "否".equals(raw)) return false;
        return rawValue;
      }
      if ("STRING".equals(t) || "TEXT".equals(t) || "ENUM".equals(t)) return raw;
    } catch (Exception ignore) {
      return rawValue;
    }
    return rawValue;
  }

  /** 点路径取值（a.b / items[0].x） */
  @SuppressWarnings("unchecked")
  private Object getPath(Map<String, Object> map, String path) {
    if (path == null || path.isEmpty()) {
      return null;
    }
    Object current = map;
    for (String seg : path.split("\\.")) {
      String key = seg;
      Integer idx = null;
      int b = seg.indexOf('[');
      if (b > 0 && seg.endsWith("]")) {
        key = seg.substring(0, b);
        idx = Integer.valueOf(seg.substring(b + 1, seg.length() - 1));
      }
      if (!(current instanceof Map)) {
        return null;
      }
      current = ((Map<String, Object>) current).get(key);
      if (idx != null) {
        if (current instanceof List && ((List<?>) current).size() > idx) {
          current = ((List<?>) current).get(idx);
        } else {
          return null;
        }
      }
      if (current == null) {
        return null;
      }
    }
    return current;
  }

  private String leafKey(String outputPath) {
    if (outputPath == null || outputPath.isEmpty()) {
      return outputPath;
    }
    int dot = outputPath.lastIndexOf('.');
    return dot < 0 ? outputPath : outputPath.substring(dot + 1);
  }

  private boolean eq(String a, String b) {
    if (a == null || a.trim().isEmpty()) {
      return b == null || b.trim().isEmpty();
    }
    return a.equals(b);
  }
}
