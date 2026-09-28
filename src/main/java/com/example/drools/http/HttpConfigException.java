package com.example.drools.http;

/**
 * HTTP 动作的【配置类】错误：密钥没配、认证配置不存在、算法不在白名单、动作不存在…
 *
 * 和"网络抖动/接口返回 500"这类运行期失败区分开：
 *   - 配置错误：无论 rule-http.fail-fast 怎么配，一律抛给调用方（配置错了就是错了，不能静默降级）
 *   - 运行期失败：由 fail-fast 决定是抛还是只记日志 + 写单据消息
 *
 * 继承 IllegalArgumentException：全局异常处理会把它转成 400 + 明确 message。
 */
public class HttpConfigException extends IllegalArgumentException {

    public HttpConfigException(String message) {
        super(message);
    }

    public HttpConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
