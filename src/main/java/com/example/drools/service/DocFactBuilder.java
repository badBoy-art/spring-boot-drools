package com.example.drools.service;

import com.example.drools.dao.RuleDocumentDao;
import com.example.drools.domain.DocFact;
import com.example.drools.entity.RuleDocumentField;
import org.mvel2.MVEL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单据事实构建器：把页面传来的单据报文 + 注册的"派生字段"表达式算成一个 {@link DocFact}。
 *
 * 为什么要有派生字段：业务口径里很多判定字段不是报文里的原始字段，而是算出来的 ——
 * 例如商品审批要看"毛利率 = (售价 - 成本) / 售价"、订单要看"折扣率 = 优惠 / 总额"。
 * 运营在「① 单据注册」里登记字段时把表达式写上（如 {@code (price - cost) / price}），
 * 这里在单据进入规则引擎之前统一算好、放进 data，规则里直接 {@code getNumber("毛利率")} 用。
 *
 * 好处：规则保持纯粹（只比大小/改状态），算法改口径只改一条字段配置，不用改规则、不用发版。
 */
@Service
public class DocFactBuilder {

    private static final Logger log = LoggerFactory.getLogger(DocFactBuilder.class);

    private final RuleDocumentDao documentDao;

    public DocFactBuilder(RuleDocumentDao documentDao) {
        this.documentDao = documentDao;
    }

    public DocFact build(String docCode, Map<String, Object> body) {
        Map<String, Object> data = body == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(body);
        DocFact fact = new DocFact(docCode, data);
        if (fact.getBizId() == null && body != null) {
            Object bizId = body.get("procInstId") != null ? body.get("procInstId")
                    : (body.get("bizId") != null ? body.get("bizId") : body.get("orderId"));
            fact.setBizId(bizId == null ? null : String.valueOf(bizId));
        }
        computeDerivedFields(fact);
        return fact;
    }

    /** 按注册的表达式计算派生字段：算不出来只记消息、不中断（缺字段会让规则不命中，比抛异常更安全） */
    public void computeDerivedFields(DocFact fact) {
        List<RuleDocumentField> fields = documentDao.findFields(fact.getDocCode());
        if (fields == null || fields.isEmpty()) {
            return;
        }
        Map<String, Object> data = fact.getData();
        List<String> failures = new ArrayList<String>();
        for (RuleDocumentField field : fields) {
            String expr = field.getExpr();
            if (expr == null || expr.trim().isEmpty()) {
                continue;
            }
            try {
                Object value = MVEL.eval(expr.trim(), data);
                if (value != null) {
                    data.put(field.getFieldKey(), value);
                    log.debug("派生字段 {}.{}({}) = {}", fact.getDocCode(), field.getFieldKey(), expr, value);
                }
            } catch (Exception e) {
                failures.add(field.getFieldName() + "[" + field.getFieldKey() + "] = " + expr + " → " + e.getMessage());
            }
        }
        for (String failure : failures) {
            // 走单据消息通道，试算结果里直接能看到哪个派生字段没算出来
            fact.addRuleMessage("派生字段计算失败：" + failure);
            log.warn("派生字段计算失败 docCode={} {}", fact.getDocCode(), failure);
        }
    }
}
