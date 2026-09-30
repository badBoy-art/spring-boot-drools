package com.example.drools.worker;

@org.springframework.web.bind.annotation.ResponseStatus(
    org.springframework.http.HttpStatus.CONFLICT)
public class WorkerCommandConflictException extends RuntimeException {
  public WorkerCommandConflictException(String message) {
    super(message);
  }
}
