import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 业务单据代码调用规则引擎的最小示例 —— 纯 JDK（HttpURLConnection），不需要引任何 SDK/jar。
 *
 * 用法：
 *   javac RuleEngineClient.java
 *   java RuleEngineClient                       # 默认 SKU 单据 + 样例参数
 *   java RuleEngineClient ORDER '{"orderId":"SO-1","totalAmount":30000,...}'
 *
 * 调用约定（就是两件事）：
 *   1) POST /rule/evaluate?docCode=<单据编码>   body = 按「单据注册」登记的字段组织的 JSON
 *   2) 读返回里的 decision（决策字段）决定业务动作；ext/messages/data 只用于排错与审计
 */
public class RuleEngineClient {

    private static final String BASE = System.getProperty("rule.engine.base", "http://localhost:8080");
    private static final int TIMEOUT_MS = 3000;

    public static void main(String[] args) throws Exception {
        String docCode = args.length > 0 ? args[0] : "SKU";
        String payload = args.length > 1 ? args[1]
                : "{\"bizId\":\"SKU-9001\",\"skuCode\":\"SKU-9001\",\"skuName\":\"羽绒服\",\"price\":100,\"cost\":65}";

        String resp = post("/rule/evaluate?docCode=" + docCode, payload);
        System.out.println("== 原始响应 ==");
        System.out.println(resp);
        System.out.println();
        System.out.println("== 业务只关心这两块 ==");
        System.out.println("evalId    : " + flatValue(resp, "evalId"));
        System.out.println("fired     : " + flatValue(resp, "fired"));
        System.out.println("decision  : " + objectBody(resp, "decision"));

        String decision = objectBody(resp, "decision");
        if (decision.isEmpty() || "{}".equals(decision)) {
            System.out.println("→ 没有任何决策字段被写出：注意 fired=0 表示没规则命中，业务要有兜底策略");
        } else {
            System.out.println("→ 业务按 decision 走分支，例如 decision.approvalLevel=" + flatValue(decision, "approvalLevel"));
        }
    }

    /** POST JSON，返回响应体（非 2xx 时也把 body 打出来，引擎的报错是中文可读的） */
    static String post(String path, String json) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
        }
        if (code != 200) {
            throw new IllegalStateException("规则引擎返回 HTTP " + code + "：" + sb);
        }
        return sb.toString();
    }

    /** 取顶层字符串/数字字段（极简解析，业务里请用自己的 JSON 库，如 Jackson/Hutool） */
    static String flatValue(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return "";
        int c = json.indexOf(':', i);
        int end = c + 1;
        while (end < json.length() && json.charAt(end) == ' ') end++;
        if (end < json.length() && json.charAt(end) == '"') {
            int close = json.indexOf('"', end + 1);
            return json.substring(end + 1, close);
        }
        int stop = end;
        while (stop < json.length() && ",} ".indexOf(json.charAt(stop)) < 0) stop++;
        return json.substring(end, stop);
    }

    /** 取某个对象字段的内层 body：objectBody(json,"decision") → {"approvalLevel":"L2"} */
    static String objectBody(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return "";
        int open = json.indexOf('{', i);
        if (open < 0) return "";
        int depth = 0;
        for (int p = open; p < json.length(); p++) {
            char ch = json.charAt(p);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return json.substring(open, p + 1);
            }
        }
        return "";
    }
}
