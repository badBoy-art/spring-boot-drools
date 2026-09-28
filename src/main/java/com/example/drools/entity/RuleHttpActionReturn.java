package com.example.drools.entity;

import lombok.Data;

/** HTTP 动作返回值映射（对应 rule_http_action_return）：响应 JSON 路径 -> 回填到单据 ext */
@Data
public class RuleHttpActionReturn {

    private Long id;
    private String actionCode;
    /** 响应 JSON 路径，如 data.riskLevel；空串表示整个响应体 */
    private String respPath;
    /** 回填字段名，写入单据 ext，后续规则用 ext["xxx"] 读 */
    private String targetField;
    /** STRING/NUMBER/DECIMAL/BOOL */
    private String targetType;
    /** 1=同时写一条单据过程消息 */
    private Integer asMessage;
    private Integer sortOrder;
}
