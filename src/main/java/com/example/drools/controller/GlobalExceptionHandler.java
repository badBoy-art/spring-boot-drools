package com.example.drools.controller;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理：把参数校验 / 规则编译错误转成带明确 message 的响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleBadRequest(IllegalArgumentException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "参数/规则校验失败");
        body.put("message", e.getMessage());
        return body;
    }

    /** rule_name 是唯一键：重名时给出可读提示，而不是把 SQL 异常抛给页面 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleDuplicate(DataIntegrityViolationException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "数据约束失败");
        // 唯一键冲突：不能一律说"规则名被占用"（实测误导过一次 —— 保存"规则类型"时模板行重复，
        // 也被报成规则名冲突，把真正原因盖住了）。按异常里的键名给出准确提示。
        String raw = e.getMostSpecificCause() == null ? "" : String.valueOf(e.getMostSpecificCause().getMessage());
        String hint;
        if (raw.contains("rule_name")) {
            hint = "规则名已被占用（rule_name 唯一），请换一个规则名；要改现有规则请点列表里的「编辑」";
        } else if (raw.contains("rule_template")) {
            hint = "该规则类型的 DRL 模板行已存在且写入冲突（rule_template 唯一键）："
                    + "请重试一次（同名类型会覆盖模板），仍失败就先把该类型删掉再新建";
        } else if (raw.contains("rule_type_field")) {
            hint = "该规则类型的参数定义行冲突（rule_type_field 唯一键）：重试保存即可（会先清后插）";
        } else if (raw.contains("rule_document_field")) {
            hint = "该单据的字段行冲突（rule_document_field 唯一键）：重试保存即可（同 doc+字段会覆盖）";
        } else {
            hint = "唯一键冲突：" + raw;
        }
        body.put("message", hint);
        return body;
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleIllegalState(IllegalStateException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "规则编译失败");
        body.put("message", e.getMessage());
        return body;
    }
}
