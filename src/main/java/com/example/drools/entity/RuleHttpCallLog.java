package com.example.drools.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** HTTP 动作调用日志（对应 rule_http_call_log）：规则调过什么、请求/响应是什么 */
@Data
public class RuleHttpCallLog {

    private Long id;
    private String actionCode;
    private String docCode;
    private String bizId;
    private String requestUrl;
    private String requestBody;
    private String responseBody;
    private Boolean success;
    private String error;
    private Long costMs;
    private LocalDateTime createTime;
}
