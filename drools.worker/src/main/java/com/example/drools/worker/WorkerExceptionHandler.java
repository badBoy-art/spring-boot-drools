package com.example.drools.worker;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class WorkerExceptionHandler {
  @ExceptionHandler(WorkerNotOwnerException.class)
  public ResponseEntity<Map<String, Object>> notOwner(WorkerNotOwnerException exception) {
    Map<String, Object> response = new LinkedHashMap<String, Object>();
    response.put("code", "SESSION_NOT_OWNED");
    response.put("ownerNode", exception.getOwnerNode());
    response.put("ownerUrl", exception.getOwnerUrl());
    response.put("message", exception.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<Map<String, Object>> unavailable(IllegalStateException exception) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("code", "WORKER_UNAVAILABLE");
    response.put("message", exception.getMessage());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> badRequest(RuntimeException exception) {
    Map<String, Object> response = new LinkedHashMap<String, Object>();
    response.put("code", "WORKER_REQUEST_FAILED");
    response.put("message", exception.getMessage());
    return ResponseEntity.badRequest().body(response);
  }
}
