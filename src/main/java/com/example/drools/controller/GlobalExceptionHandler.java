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
        body.put("message", "规则名已被占用（rule_name 唯一），请换一个规则名；要改现有规则请点列表里的「编辑」");
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
