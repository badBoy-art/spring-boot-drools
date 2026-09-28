package com.example.drools.http;

import com.example.drools.dao.RuleHttpActionDao;
import com.example.drools.dao.RuleHttpAuthDao;
import com.example.drools.domain.Customer;
import com.example.drools.domain.Order;
import com.example.drools.domain.OrderItem;
import com.example.drools.domain.Product;
import com.example.drools.entity.RuleHttpAction;
import com.example.drools.entity.RuleHttpActionReturn;
import com.example.drools.entity.RuleHttpAuth;
import com.example.drools.entity.RuleHttpCallLog;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.runtime.KieSession;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本次升级的端到端验证（不需要 MySQL、不依赖外网）：
 *   - 起一个本机 HttpServer 当"外部系统"
 *   - 入参模板：常量 / ${对象属性} / ${表达式} 三种写法都对
 *   - 命中规则 → 真的发出了 HTTP 请求 → 返回值按映射回填到单据 ext
 *   - 回填后的值继续参与后续规则（ext 作为 LHS 条件）
 *   - 状态守卫保证同一单据只调一次（不会因为 update() 反复调接口）
 */
class HttpActionFlowTest {

    private static HttpServer server;
    private static final List<String> REQUEST_BODIES = new CopyOnWriteArrayList<String>();

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/risk", exchange -> {
            String body = readAllQuiet(exchange.getRequestBody());
            REQUEST_BODIES.add(body);
            // 简化判定：金额 >= 5000 判 HIGH
            String riskLevel = body.contains("\"amount\":6000") || body.contains("\"amount\":5600") ? "HIGH" : "LOW";
            String response = "{\"code\":0,\"data\":{\"riskLevel\":\"" + riskLevel + "\",\"score\":88}}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.createContext("/points", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            REQUEST_BODIES.add("QUERY:" + query);
            byte[] bytes = "{\"code\":0,\"data\":{\"points\":100}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
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
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int port() {
        return server.getAddress().getPort();
    }

    /** 用假的 DAO（不连库）喂配置：动作 + 返回值映射，并把调用日志记到内存里 */
    private RuleHttpActionDao fakeDao(final RuleHttpAction action, final List<RuleHttpActionReturn> returns,
                                      final List<RuleHttpCallLog> logs) {
        return new RuleHttpActionDao(null) {
            @Override
            public RuleHttpAction findByCode(String actionCode) {
                return action != null && action.getActionCode().equals(actionCode) ? action : null;
            }

            @Override
            public List<RuleHttpActionReturn> findReturns(String actionCode) {
                return returns;
            }

            @Override
            public void insertLog(RuleHttpCallLog log) {
                logs.add(log);
            }
        };
    }

    private HttpActionGateway gateway(RuleHttpActionDao dao, boolean failFast) {
        RuleHttpProperties props = new RuleHttpProperties();
        props.setDomains(Collections.singletonMap("local", "http://127.0.0.1:" + port()));
        props.setDefaultTimeoutMs(2000);
        props.setFailFast(failFast);
        props.setSecrets(secrets());
        return new HttpActionGateway(dao, props, new HttpAuthSupport(authDao(null), props), new com.example.drools.http.DomainResolver(null, props));
    }

    /** 测试用密钥库（等价于 application.yml 的 rule-http.secrets） */
    static Map<String, String> secrets() {
        Map<String, String> secrets = new LinkedHashMap<String, String>();
        secrets.put("staticToken", "test-static-token");
        secrets.put("jwtSecret", "test-jwt-secret-0123456789abcdef");
        secrets.put("aesKey", "hex:00112233445566778899aabbccddeeff");
        secrets.put("hmacKey", "hex:0102030405060708090a0b0c0d0e0f10");
        secrets.put("password", "pwd-123");
        return secrets;
    }

    /** 假认证 DAO：按需返回一个认证配置（不连库） */
    static RuleHttpAuthDao authDao(final RuleHttpAuth auth) {
        return new RuleHttpAuthDao(null) {
            @Override
            public RuleHttpAuth findByCode(String authCode) {
                return auth == null ? null
                        : (auth.getAuthCode().equals(authCode) ? auth : null);
            }
        };
    }

    private RuleHttpAction action(String code, String method, String path, String bodyTemplate) {
        RuleHttpAction a = new RuleHttpAction();
        a.setActionCode(code);
        a.setActionName("风控查询");
        a.setDocCode("ORDER");
        a.setMethod(method);
        a.setDomainKey("local");
        a.setPath(path);
        a.setHeadersJson("{\"X-Tenant\":\"demo\"}");
        a.setBodyTemplate(bodyTemplate);
        a.setTimeoutMs(2000);
        a.setStatus(1);
        return a;
    }

    private Order order(double amount) {
        Order order = new Order();
        order.setOrderId("T-1001");
        order.setCustomer(new Customer(1L, "张三", "VIP", "新疆", 30, true));
        Product product = new Product(1L, "iPhone", "电子产品", amount, 100, true);
        order.setItems(Arrays.asList(new OrderItem(product, 1)));
        order.setTotalAmount(amount);
        return order;
    }

    @Test
    @DisplayName("嵌套对象 / 数组下标 / 方法调用都能取值（customer.level、items[0].product.category、items.size()）")
    void renderNestedObjectAndArrayPaths() {
        RuleHttpAction a = action("RISK_CHECK", "POST", "/risk",
                "{\"level\":\"${customer.level}\",\"region\":\"${customer.region}\","
                        + "\"category\":\"${items[0].product.category}\",\"count\":${items.size()},"
                        + "\"firstPrice\":${items[0].product.price},\"qty\":${items[0].quantity}}");
        HttpActionGateway gateway = gateway(fakeDao(a, new ArrayList<RuleHttpActionReturn>(), new ArrayList<RuleHttpCallLog>()), false);
        String rendered = gateway.renderTemplate(a.getBodyTemplate(), gateway.toContext(order(6000)));
        assertTrue(rendered.contains("\"level\":\"VIP\""), rendered);
        assertTrue(rendered.contains("\"region\":\"新疆\""), rendered);
        assertTrue(rendered.contains("\"category\":\"电子产品\""), rendered);
        assertTrue(rendered.contains("\"count\":1"), rendered);
        assertTrue(rendered.contains("\"firstPrice\":6000.0"), rendered);
        assertTrue(rendered.contains("\"qty\":1"), rendered);
    }

    @Test
    @DisplayName("入参模板：常量 + 对象属性 + 计算表达式 三种写法都渲染正确")
    void renderTemplateSupportsConstantFieldAndExpression() {
        RuleHttpAction a = action("RISK_CHECK", "POST", "/risk",
                "{\"bizId\":\"${orderId}\",\"level\":\"${customer.level}\","
                        + "\"amount\":${totalAmount},\"riskScore\":${totalAmount * 0.01},"
                        + "\"itemCount\":${items.size()},\"channel\":\"规则引擎\"}");
        HttpActionGateway gateway = gateway(fakeDao(a, new ArrayList<RuleHttpActionReturn>(), new ArrayList<RuleHttpCallLog>()), false);

        String rendered = gateway.renderTemplate(a.getBodyTemplate(), gateway.toContext(order(6000)));

        assertTrue(rendered.contains("\"bizId\":\"T-1001\""), rendered);              // 对象属性（引号内 → 字符串）
        assertTrue(rendered.contains("\"level\":\"VIP\""), rendered);                 // 嵌套属性 customer.level
        assertTrue(rendered.contains("\"amount\":6000.0"), rendered);                 // 属性直接当数字
        assertTrue(rendered.contains("\"riskScore\":60.0"), rendered);                // 计算结果
        assertTrue(rendered.contains("\"itemCount\":1"), rendered);                   // 表达式 items.size()
        assertTrue(rendered.contains("\"channel\":\"规则引擎\""), rendered);           // 常量
    }

    @Test
    @DisplayName("调接口 + 返回值回填 ext + 写调用日志")
    void invokeMapsResponseIntoExt() {
        RuleHttpAction a = action("RISK_CHECK", "POST", "/risk", "{\"amount\":${totalAmount},\"bizId\":\"${orderId}\"}");
        RuleHttpActionReturn level = new RuleHttpActionReturn();
        level.setRespPath("data.riskLevel");
        level.setTargetField("riskLevel");
        level.setTargetType("STRING");
        level.setAsMessage(1);
        RuleHttpActionReturn score = new RuleHttpActionReturn();
        score.setRespPath("data.score");
        score.setTargetField("riskScore");
        score.setTargetType("NUMBER");
        List<RuleHttpCallLog> logs = new ArrayList<RuleHttpCallLog>();

        HttpActionGateway gateway = gateway(fakeDao(a, Arrays.asList(level, score), logs), false);
        Order order = order(6000);
        Map<String, Object> mapped = gateway.invoke("RISK_CHECK", order);

        assertEquals("HIGH", mapped.get("riskLevel"));
        assertEquals(88L, mapped.get("riskScore"));
        assertEquals("HIGH", order.getExt().get("riskLevel"));
        assertEquals("OK", order.getExt().get("RISK_CHECK"), "要写调用标记，模板靠它做状态守卫");
        assertEquals(1, logs.size());
        assertTrue(logs.get(0).getSuccess());
        assertTrue(logs.get(0).getRequestBody().contains("\"amount\":6000.0"));
        assertTrue(logs.get(0).getResponseBody().contains("HIGH"));
        assertNotNull(logs.get(0).getCostMs());
    }

    @Test
    @DisplayName("GET 动作：渲染结果拼成 query")
    void getActionUsesQueryString() {
        RuleHttpAction a = action("POINT_QUERY", "GET", "/points", "{\"amount\":${totalAmount},\"level\":\"${customer.level}\"}");
        RuleHttpActionReturn points = new RuleHttpActionReturn();
        points.setRespPath("data.points");
        points.setTargetField("points");
        points.setTargetType("NUMBER");
        HttpActionGateway gateway = gateway(fakeDao(a, Collections.singletonList(points), new ArrayList<RuleHttpCallLog>()), false);

        Order order = order(1000);
        gateway.invoke("POINT_QUERY", order);

        assertEquals(100L, ((Number) order.getExt().get("points")).longValue());
        assertTrue(REQUEST_BODIES.stream().anyMatch(s -> s.startsWith("QUERY:amount=1000.0")), REQUEST_BODIES.toString());
    }

    @Test
    @DisplayName("接口失败：默认不抛异常（记日志 + 写单据消息），fail-fast 打开才抛")
    void failureHandling() {
        // 指向一个不存在的路径（404）来模拟失败
        RuleHttpAction a = action("BAD_ACTION", "POST", "/not-exist", "{}");
        List<RuleHttpCallLog> logs = new ArrayList<RuleHttpCallLog>();
        Order order = order(1000);

        HttpActionGateway tolerant = gateway(fakeDao(a, new ArrayList<RuleHttpActionReturn>(), logs), false);
        tolerant.invoke("BAD_ACTION", order);
        assertEquals(1, logs.size());
        assertTrue(!logs.get(0).getSuccess());
        assertTrue(order.getMessages().stream().anyMatch(m -> m.contains("返回 HTTP 404")), order.getMessages().toString());
        assertEquals("OK", order.getExt().get("BAD_ACTION"), "失败也要写标记，避免反复重试");

        HttpActionGateway failFast = gateway(fakeDao(a, new ArrayList<RuleHttpActionReturn>(), logs), true);
        assertThrows(IllegalStateException.class, () -> failFast.invoke("BAD_ACTION", order(1000)));
    }

    @Test
    @DisplayName("规则里调用：命中 → 真的发 HTTP → ext 回填 → 后续规则用 ext 继续判定；且同一单据只调一次")
    void ruleInvokesHttpAndNextRuleUsesResult() {
        RuleHttpAction a = action("RISK_CHECK", "POST", "/risk",
                "{\"bizId\":\"${orderId}\",\"amount\":${totalAmount},\"level\":\"${customer.level}\","
                        + "\"riskScore\":${totalAmount * 0.01},\"itemCount\":${items.size()},\"channel\":\"规则引擎\"}");
        RuleHttpActionReturn level = new RuleHttpActionReturn();
        level.setRespPath("data.riskLevel");
        level.setTargetField("riskLevel");
        level.setTargetType("STRING");
        level.setAsMessage(1);
        List<RuleHttpCallLog> logs = new ArrayList<RuleHttpCallLog>();
        HttpActionGateway gateway = gateway(fakeDao(a, Collections.singletonList(level), logs), false);

        // 与 db/http-action.sql 里播种的两个模板完全一致，等于顺带验证了 SQL 播种的模板能编译
        String drl =
                "package com.example.drools.dynamic;\n" +
                "dialect \"mvel\"\n" +
                "import com.example.drools.domain.Order;\n" +
                "global com.example.drools.http.HttpActionGateway httpActionGateway;\n" +
                "rule \"HTTP_ACTION_RISK\"\n" +
                "    when\n" +
                "        $o : Order( rejected == false, ext[\"RISK_CHECK\"] == null, totalAmount >= 5000 )\n" +
                "    then\n" +
                "        httpActionGateway.invoke(\"RISK_CHECK\", $o);\n" +
                "        update($o);\n" +
                "        $o.addMessage(\"已调用接口[RISK_CHECK]，金额 \" + $o.getTotalAmount());\n" +
                "end\n" +
                "rule \"HTTP_RESULT_GUARD\"\n" +
                "    when\n" +
                "        $o : Order( rejected == false, ext[\"riskLevel\"] == \"HIGH\" )\n" +
                "    then\n" +
                "        $o.setRejected(true);\n" +
                "        update($o);\n" +
                "        $o.addMessage(\"接口返回 riskLevel=HIGH，订单被拦截\");\n" +
                "end\n";

        KieServices ks = KieServices.Factory.get();
        KieFileSystem kfs = ks.newKieFileSystem();
        kfs.write("src/main/resources/rules/http-action.drl", drl);
        KieBuilder builder = ks.newKieBuilder(kfs);
        builder.buildAll();
        assertTrue(!builder.getResults().hasMessages(Message.Level.ERROR), "DRL 编译失败: " + builder.getResults().getMessages());
        KieBase base = ks.newKieContainer(ks.getRepository().getDefaultReleaseId()).getKieBase();

        REQUEST_BODIES.clear();
        Order order = order(6000);
        KieSession session = base.newKieSession();
        try {
            session.setGlobal("httpActionGateway", gateway);
            session.insert(order);
            session.insert(order.getCustomer());
            for (OrderItem item : order.getItems()) {
                session.insert(item);
                session.insert(item.getProduct());
            }
            int fired = session.fireAllRules(50);
            assertTrue(fired >= 2, "两条规则都该点火, fired=" + fired);
        } finally {
            session.dispose();
        }

        // 1) 规则真的发出了 HTTP 请求（被本机 stub 收到）
        assertEquals(1, REQUEST_BODIES.size(), "同一单据同一动作只该调一次（状态守卫 + 调用标记）: " + REQUEST_BODIES);
        assertTrue(REQUEST_BODIES.get(0).contains("\"channel\":\"规则引擎\""), REQUEST_BODIES.get(0));
        assertTrue(REQUEST_BODIES.get(0).contains("\"riskScore\":60.0"), REQUEST_BODIES.get(0));
        // 2) 返回值回填到 ext
        assertEquals("HIGH", order.getExt().get("riskLevel"));
        // 3) 回填的值被后续规则用上了
        assertTrue(order.isRejected());
        assertTrue(order.getMessages().stream().anyMatch(m -> m.contains("riskLevel=HIGH")), order.getMessages().toString());
        assertTrue(order.getMessages().stream().anyMatch(m -> m.contains("订单被拦截")), order.getMessages().toString());
        // 4) 落了调用日志
        assertEquals(1, logs.size());
    }

    @Test
    @DisplayName("未达阈值：不调接口")
    void belowThresholdNoCall() {
        RuleHttpAction a = action("RISK_CHECK", "POST", "/risk", "{\"amount\":${totalAmount}}");
        List<RuleHttpCallLog> logs = new ArrayList<RuleHttpCallLog>();
        HttpActionGateway gateway = gateway(fakeDao(a, new ArrayList<RuleHttpActionReturn>(), logs), false);

        String drl =
                "package com.example.drools.dynamic;\n" +
                "dialect \"mvel\"\n" +
                "import com.example.drools.domain.Order;\n" +
                "global com.example.drools.http.HttpActionGateway httpActionGateway;\n" +
                "rule \"HTTP_ACTION_RISK\"\n" +
                "    when\n" +
                "        $o : Order( rejected == false, ext[\"RISK_CHECK\"] == null, totalAmount >= 5000 )\n" +
                "    then\n" +
                "        httpActionGateway.invoke(\"RISK_CHECK\", $o);\n" +
                "end\n";
        KieServices ks = KieServices.Factory.get();
        KieFileSystem kfs = ks.newKieFileSystem();
        kfs.write("src/main/resources/rules/http-action2.drl", drl);
        KieBuilder builder = ks.newKieBuilder(kfs);
        builder.buildAll();
        KieBase base = ks.newKieContainer(ks.getRepository().getDefaultReleaseId()).getKieBase();

        REQUEST_BODIES.clear();
        Order order = order(1000);
        KieSession session = base.newKieSession();
        try {
            session.setGlobal("httpActionGateway", gateway);
            session.insert(order);
            session.fireAllRules(50);
        } finally {
            session.dispose();
        }
        assertTrue(REQUEST_BODIES.isEmpty(), "未达阈值不该调: " + REQUEST_BODIES);
        assertEquals(0, logs.size());
    }

    private Map<String, Object> ctx() {
        Map<String, Object> ctx = new LinkedHashMap<String, Object>();
        ctx.put("orderId", "T-1");
        ctx.put("totalAmount", 6000);
        return ctx;
    }

    @Test
    @DisplayName("动作不存在 -> 抛配置异常")
    void actionNotFound() {
        HttpActionGateway gateway = gateway(fakeDao(null, new ArrayList<RuleHttpActionReturn>(), new ArrayList<RuleHttpCallLog>()), false);
        assertEquals("T-1", ctx().get("orderId"));
        assertThrows(com.example.drools.http.HttpConfigException.class,
                () -> gateway.invokeWithContext("NOT_EXIST", ctx(), null));
    }
}
