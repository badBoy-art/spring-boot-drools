package com.example.drools.http;

import com.example.drools.dao.RuleHttpActionDao;
import com.example.drools.domain.DocFact;
import com.example.drools.entity.RuleHttpAction;
import com.example.drools.entity.RuleHttpActionReturn;
import com.example.drools.entity.RuleHttpCallLog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "业务对象可配置"的验证：单据不写 Java 类，用通用 DocFact（Map 载体）+ 注册字段就能进规则引擎；
 * 规则命中后调 HTTP 接口 → 返回值回填 ext → 后续规则按返回值继续判定。
 * 桩服务在本机起，真实发 HTTP。
 */
class DocFactRuleTest {

    private static final List<String> BODIES = new CopyOnWriteArrayList<String>();
    private static HttpServer server;

    private static final String TEMPLATE_BODY =
            "{\"procInstId\":\"${procInstId}\",\"processKey\":\"${processKey}\",\"starter\":\"${starter.name}\","
                    + "\"amount\":${variables.amount},\"items\":${items[0].sku},\"node\":\"${currentNode}\"}";

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/wf", exchange -> {
            String body = readAllQuiet(exchange.getRequestBody());
            BODIES.add(body);
            double amount = 0;
            int idx = body.indexOf("\"amount\":");
            if (idx > 0) {
                String tail = body.substring(idx + 9);
                amount = Double.parseDouble(tail.substring(0, tail.indexOf(',') > 0 ? tail.indexOf(',') : tail.indexOf('}')));
            }
            String node = amount >= 10000 ? "风控复核" : "部门审批";
            respond(exchange, "{\"code\":0,\"data\":{\"procInstId\":\"WF-9001\",\"status\":\"RUNNING\","
                    + "\"approveNode\":\"" + node + "\"}}");
        });
        server.start();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private RuleHttpAction workflowAction() {
        RuleHttpAction action = new RuleHttpAction();
        action.setActionCode("WORKFLOW_START");
        action.setActionName("流程引擎-启动流程");
        action.setActionCategory("流程引擎接口");
        action.setDocCode("WF");
        action.setMethod("POST");
        action.setDomainKey("local");
        action.setPath("/wf");
        action.setBodyTemplate(TEMPLATE_BODY);
        action.setTimeoutMs(3000);
        action.setStatus(1);
        return action;
    }

    private List<RuleHttpActionReturn> returns() {
        List<RuleHttpActionReturn> list = new ArrayList<RuleHttpActionReturn>();
        RuleHttpActionReturn node = new RuleHttpActionReturn();
        node.setRespPath("data.approveNode");
        node.setTargetField("wfApproveNode");
        node.setTargetType("STRING");
        node.setAsMessage(1);
        list.add(node);
        return list;
    }

    private HttpActionGateway gateway() {
        RuleHttpProperties props = new RuleHttpProperties();
        props.setDomains(Collections.singletonMap("local", "http://127.0.0.1:" + server.getAddress().getPort()));
        props.setSecrets(HttpActionFlowTest.secrets());
        props.setDefaultTimeoutMs(2000);
        props.setFailFast(false);
        final RuleHttpAction action = workflowAction();
        RuleHttpActionDao dao = new RuleHttpActionDao(null) {
            @Override
            public RuleHttpAction findByCode(String code) {
                return "WORKFLOW_START".equals(code) ? action : null;
            }

            @Override
            public List<RuleHttpActionReturn> findReturns(String code) {
                return returns();
            }

            @Override
            public void insertLog(RuleHttpCallLog log) {
            }
        };
        return new HttpActionGateway(dao, props, new HttpAuthSupport(HttpActionFlowTest.authDao(null), props), new com.example.drools.http.DomainResolver(null, props));
    }

    /** 规则就用 docs 里那套：命中 → 调接口 → 回填 → 后续规则按返回值继续 */
    private static final String DRL =
            "package com.example.drools.dynamic;\n"
                    + "dialect \"mvel\"\n"
                    + "import com.example.drools.domain.DocFact;\n"
                    + "global com.example.drools.http.HttpActionGateway httpActionGateway;\n\n"
                    + "rule \"WF_START_10000\"\n"
                    + "    when\n"
                    + "        $d : DocFact( docCode == \"WF\", ext[\"WORKFLOW_START\"] == null, getNumber(\"variables.amount\") >= 10000 )\n"
                    + "    then\n"
                    + "        httpActionGateway.invoke(\"WORKFLOW_START\", $d);\n"
                    + "        update($d);\n"
                    + "        $d.addRuleMessage(\"流程实例[\" + $d.getString(\"procInstId\") + \"]命中，已启动流程\");\n"
                    + "end\n\n"
                    + "rule \"WF_RISK_NODE_GUARD\"\n"
                    + "    when\n"
                    + "        $d : DocFact( docCode == \"WF\", ext[\"wfApproveNode\"] == \"风控复核\", ext[\"marked_wfApproveNode\"] == null )\n"
                    + "    then\n"
                    + "        $d.getExt().put(\"marked_wfApproveNode\", true);\n"
                    + "        $d.getExt().put(\"requires_risk_review\", true);\n"
                    + "        $d.addRuleMessage(\"接口返回值 approveNode=风控复核 → 标记需要风控复核\");\n"
                    + "        update($d);\n"
                    + "end\n";

    private DocFact wfFact(double amount) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("procInstId", "WF-1001");
        data.put("processKey", "order_approve");
        data.put("currentNode", "risk_review");
        Map<String, Object> variables = new LinkedHashMap<String, Object>();
        variables.put("amount", amount);
        variables.put("bizType", "ORDER");
        data.put("variables", variables);
        Map<String, Object> starter = new LinkedHashMap<String, Object>();
        starter.put("name", "张三");
        starter.put("dept", "采购部");
        data.put("starter", starter);
        data.put("items", new ArrayList<Object>(Arrays.asList(Collections.singletonMap("sku", "SKU-9"))));
        return new DocFact("WF", data);
    }

    private KieSession sessionFor(HttpActionGateway gateway) {
        KieHelper helper = new KieHelper();
        helper.addContent(DRL, org.kie.api.io.ResourceType.DRL);
        KieBase base = helper.build();
        KieSession session = base.newKieSession();
        session.setGlobal("httpActionGateway", gateway);
        return session;
    }

    @Test
    @DisplayName("通用单据 DocFact：注册字段(含嵌套) → 命中规则 → 调流程引擎接口 → 返回值回填 → 后续规则继续判定")
    void docFactDrivesRulesAndHttpAction() throws Exception {
        BODIES.clear();
        DocFact fact = wfFact(12000);
        KieSession session = sessionFor(gateway());
        int fired;
        try {
            session.insert(fact);
            fired = session.fireAllRules(200);
        } finally {
            session.dispose();
        }

        // 0) 收敛性：带状态守卫后不该反复点火（mvel 方言 + update() 的经典坑，烧满上限就是没收敛）
        assertTrue(fired <= 3, "点火次数异常（没收敛？）: " + fired);
        // 1) 真发了 HTTP，且只发一次（状态守卫 + 调用标记）
        assertEquals(1, BODIES.size(), "同一单据同一动作只该调一次: " + BODIES);
        String body = BODIES.get(0);
        // 2) 嵌套字段渲染正确：variables.amount / starter.name / items[0].sku / 顶层字段
        assertTrue(body.contains("\"amount\":12000.0"), body);
        assertTrue(body.contains("\"starter\":\"张三\""), body);
        assertTrue(body.contains("\"items\":\"SKU-9\""), body);
        assertTrue(body.contains("\"procInstId\":\"WF-1001\""), body);
        assertTrue(body.contains("\"processKey\":\"order_approve\""), body);
        // 3) 返回值回填 ext，后续规则读到并继续执行
        assertEquals("风控复核", fact.getExt().get("wfApproveNode"));
        assertEquals(Boolean.TRUE, fact.getExt().get("requires_risk_review"));
        assertEquals("OK", fact.getExt().get("WORKFLOW_START"));
        // 4) 过程消息（各一次，说明没有反复点火）
        long hitCount = fact.getMessages().stream().filter(m -> m.contains("命中，已启动流程")).count();
        assertEquals(1, hitCount, "命中消息应只有一条: " + fact.getMessages());
        assertTrue(fact.getMessages().toString().contains("标记需要风控复核"), fact.getMessages().toString());
    }

    @Test
    @DisplayName("阈值以下不调接口；不同单据编码互不干扰")
    void belowThresholdAndOtherDoc() throws Exception {
        BODIES.clear();
        // 阈值以下
        DocFact low = wfFact(5000);
        KieSession session = sessionFor(gateway());
        try {
            session.insert(low);
            session.fireAllRules(200);
        } finally {
            session.dispose();
        }
        assertTrue(BODIES.isEmpty(), "未达阈值不该调接口: " + BODIES);
        assertTrue(low.getExt().isEmpty(), "没调接口就不该有 ext 回填: " + low.getExt());

        // 另一个单据（ORDER）不该命中 WF 规则
        BODIES.clear();
        DocFact order = new DocFact("ORDER", Collections.singletonMap("totalAmount", 99999));
        KieSession session2 = sessionFor(gateway());
        try {
            session2.insert(order);
            int fired = session2.fireAllRules(200);
            assertEquals(0, fired, "ORDER 单据不该命中 WF 规则");
        } finally {
            session2.dispose();
        }
        assertTrue(BODIES.isEmpty(), BODIES.toString());
    }

    private static void respond(HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readAllQuiet(InputStream is) {
        try {
            return readAll(is);
        } catch (Exception e) {
            return "";
        }
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
