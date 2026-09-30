package com.example.drools.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 明细行事实：单据里每个数组对象（is_collection=1，如订单的 items）的**每一条**， 在进入规则引擎前被拆成一条独立的 {@link DocItem} 事实插入会话。
 *
 * <p>这样规则就能直接用原生 Drools 的多事实模式做「明细行校验」： · 每条（EACH）：
 *
 * <pre>$i : DocItem( collection == "items", getNumber("price") <= 0 )</pre>
 *
 * 天然逐行点火； · 第 N 条（NTH）：
 *
 * <pre>$i : DocItem( collection == "items", index == 2, getNumber("price") > 100 )</pre>
 *
 * · 顺带解锁 exists / not / forall / accumulate / collect（见 docs/drools-gap-analysis.md）。
 *
 * <p>collection：明细对象 key（如 items），区分同一单据里多个数组对象； index ：第几条（0-based），用于「第 N 条」校验； data ：该行的字段 Map
 * —— 字段名是**行内相对名**（price / sku / quantity），不含 items[0]. 前缀。
 *
 * <p>逐项判定结果写入当前行的 ext；聚合校验结果写入主事实 DocFact 的 ext。
 */
@Data
public class DocItem implements java.io.Serializable {
  private static final long serialVersionUID = 1L;

  /** 单据编码，如 ORDER */
  private String docCode;

  /** 明细对象 key，如 items */
  private String collection;

  /** 第几条（0-based） */
  private int index;

  /** 该行的字段 Map（行内相对字段名） */
  private Map<String, Object> data = new LinkedHashMap<String, Object>();

  /** 当前明细项独立的规则输出，不与其它 SKU/区域项共享。 */
  private Map<String, Object> ext = new LinkedHashMap<String, Object>();

  public DocItem() {}

  public DocItem(String docCode, String collection, int index, Map<String, Object> data) {
    this.docCode = docCode;
    this.collection = collection;
    this.index = index;
    this.data = data == null ? new LinkedHashMap<String, Object>() : data;
  }

  /** 行内取值：支持单层（price）与嵌套（product.category）、数组下标（sub[0].x） */
  @SuppressWarnings("unchecked")
  public Object get(String path) {
    if (path == null || path.isEmpty()) {
      return null;
    }
    Object current = data;
    for (String segment : path.split("\\.")) {
      String key = segment;
      Integer idx = null;
      int bracket = segment.indexOf('[');
      if (bracket > 0 && segment.endsWith("]")) {
        key = segment.substring(0, bracket);
        idx = Integer.valueOf(segment.substring(bracket + 1, segment.length() - 1));
      }
      if (!(current instanceof Map)) {
        return null;
      }
      current = ((Map<String, Object>) current).get(key);
      if (idx != null) {
        if (current instanceof List && ((List<Object>) current).size() > idx) {
          current = ((List<Object>) current).get(idx);
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

  /** 取数值：缺失或非数字返回 0（Drools 约束里比大小用它最稳） */
  public double getNumber(String path) {
    Object value = get(path);
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    if (value == null) {
      return 0d;
    }
    try {
      return Double.parseDouble(String.valueOf(value));
    } catch (NumberFormatException e) {
      return 0d;
    }
  }

  /** 取字符串：缺失返回 "" */
  public String getString(String path) {
    Object value = get(path);
    return value == null ? "" : String.valueOf(value);
  }

  /** 取布尔：缺失返回 false */
  public boolean getBool(String path) {
    Object value = get(path);
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    return value != null && "true".equalsIgnoreCase(String.valueOf(value));
  }
}
