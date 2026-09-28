package com.example.drools.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组合规则表（页面版决策表，对应 rule_combination_table）：
 * 一张表 = 一组条件列 + 一个动作 + N 行组合；每行编译成一条规则，整张表一次发布。
 */
@Data
public class RuleCombinationTable {

    private Long id;
    private String assetKey;
    private String assetName;
    /** 绑定单据（factClass=DocFact 时必填） */
    private String docCode;
    /** 主事实：Order / DocFact */
    private String factClass;
    private Integer salience;
    /** 条件列定义 JSON */
    private String columnsJson;
    /** 动作定义 JSON */
    private String actionJson;
    /** 行数据 JSON（每行 = 一条组合） */
    private String rowsJson;
    /** 生成出来的 DRL（发布时写入） */
    private String drlContent;
    private Integer rowCount;
    private Integer version;
    /** 0 草稿 / 1 生效 / 2 停用 */
    private Integer status;
    private String updatedBy;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
