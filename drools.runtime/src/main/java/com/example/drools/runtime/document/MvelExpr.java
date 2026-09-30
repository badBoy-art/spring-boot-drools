package com.example.drools.runtime.document;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.mvel2.MVEL;
import org.mvel2.ParserContext;

/**
 * MVEL 表达式求值的统一入口：预置 round(x, n) 取位助手。
 *
 * <p>派生字段（DocumentFactBuilder）与返回值结构的 EXPR 源（DocumentOutputAssembler）共用它， 这样运营在写「多字段算式」时可以直接写 {@code
 * round((售价 - 成本) / 成本, 4)} 得到 指定小数位（如 0.5385），不用关心 Java 的浮点精度。
 */
public final class MvelExpr {

  private MvelExpr() {}

  /** 四舍五入保留 scale 位小数（0.5384615 → round(x,4) = 0.5385） */
  public static double round(double value, int scale) {
    if (scale < 0) {
      scale = 0;
    }
    return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
  }

  public static Object eval(String expr, Map<String, Object> vars) {
    try {
      ParserContext ctx = new ParserContext();
      ctx.addImport("round", MvelExpr.class.getMethod("round", double.class, int.class));
      return MVEL.executeExpression(MVEL.compileExpression(expr, ctx), vars);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("表达式求值失败: " + expr, e);
    }
  }
}
