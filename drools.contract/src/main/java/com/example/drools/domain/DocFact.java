package com.example.drools.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 通用单据事实：任何注册进中台的单据（订单 / 商品 / 流程实例 …）都能用它进规则引擎， 不需要为每种单据写一个 Java 类 —— "业务对象可配置"的载体。
 *
 * <p>data：单据注册里登记的字段（嵌套 Map/List 原样存） ext ：规则计算得到的决策值（后续规则用 get("xxx") 继续判定） 取数辅助（Drools 约束里直接调用）：
 * get("variables.amount") 取任意类型（支持 a.b.c、items[0].sku） getNumber("variables.amount")
 * 取数值（缺失=0，约束里比大小用它，避免类型不匹配） getString("currentNode") 取字符串（缺失=""） getBool("vip") 取布尔（缺失=false）
 */
@Data
public class DocFact implements java.io.Serializable {
  private static final long serialVersionUID = 1L;

  /** 单据编码，如 ORDER / WF */
  private String docCode;

  /** 业务主键，便于日志排查 */
  private String bizId;

  /** 单据参数（注册的字段值） */
  private Map<String, Object> data = new LinkedHashMap<String, Object>();

  /** 接口返回值回填区 */
  private Map<String, Object> ext = new LinkedHashMap<String, Object>();

  /** 规则过程消息 */
  private List<String> messages = new ArrayList<String>();

  /** 按注册对象的完整 valuePath 关联明细事实，供结构化返回按原数组顺序回填。 */
  private Map<String, List<DocItem>> lineItemsByPath = new LinkedHashMap<String, List<DocItem>>();

  public DocFact() {}

  public DocFact(String docCode, Map<String, Object> data) {
    this.docCode = docCode;
    this.data = data == null ? new LinkedHashMap<String, Object>() : data;
  }

  /** 按路径取数：支持 a.b、items[0].product.category */
  @SuppressWarnings("unchecked")
  public Object get(String path) {
    if (path == null || path.isEmpty()) {
      return null;
    }
    Object current = data;
    for (String segment : path.split("\\.")) {
      String key = segment;
      Integer index = null;
      int bracket = segment.indexOf('[');
      if (bracket > 0 && segment.endsWith("]")) {
        key = segment.substring(0, bracket);
        index = Integer.valueOf(segment.substring(bracket + 1, segment.length() - 1));
      }
      if (current instanceof Map) {
        current = ((Map<String, Object>) current).get(key);
      } else {
        return null;
      }
      if (index != null) {
        if (current instanceof List && ((List<Object>) current).size() > index) {
          current = ((List<Object>) current).get(index);
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

  /** 取数值：缺失或非数字返回 0（Drools 约束里用它比大小最稳） */
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

  /** 取字符串：缺失返回"" */
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

  public Object getExtValue(String key) {
    return ext.get(key);
  }

  public void addRuleMessage(String message) {
    messages.add(message);
  }
}
