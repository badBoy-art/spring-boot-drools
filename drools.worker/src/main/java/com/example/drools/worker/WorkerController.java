package com.example.drools.worker;

import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/worker")
public class WorkerController {
  private final WorkerRuntimeService runtime;

  public WorkerController(WorkerRuntimeService runtime) {
    this.runtime = runtime;
  }

  @PostMapping("/sessions")
  public Map<String, Object> create(@RequestBody Map<String, Object> request) {
    return runtime.createSession(request);
  }

  @PostMapping("/sessions/{id}/facts")
  public Map<String, Object> insert(
      @PathVariable("id") String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> request) {
    return runtime.idempotent(id, key, "insert", request, () -> runtime.insert(id, request));
  }

  @PutMapping("/sessions/{id}/facts")
  public Map<String, Object> update(
      @PathVariable("id") String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody Map<String, Object> request) {
    return runtime.idempotent(id, key, "update", request, () -> runtime.update(id, request));
  }

  @DeleteMapping("/sessions/{id}/facts")
  public Map<String, Object> retract(
      @PathVariable("id") String id, @RequestParam("factHandle") String handle) {
    return runtime.retract(id, handle);
  }

  @PostMapping("/sessions/{id}/fire")
  public Map<String, Object> fire(
      @PathVariable("id") String id,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody(required = false) Map<String, Object> request) {
    return runtime.idempotent(id, key, "fire", request, () -> runtime.fire(id, request));
  }

  @PostMapping("/sessions/{id}/fire-until-halt")
  public Map<String, Object> start(@PathVariable("id") String id) {
    return runtime.start(id);
  }

  @PostMapping("/sessions/{id}/halt")
  public Map<String, Object> halt(@PathVariable("id") String id) {
    return runtime.halt(id);
  }

  @PostMapping("/sessions/{id}/checkpoint")
  public Map<String, Object> checkpoint(@PathVariable("id") String id) {
    return runtime.checkpoint(id);
  }

  @PostMapping("/sessions/{id}/globals")
  public Map<String, Object> global(
      @PathVariable("id") String id, @RequestBody Map<String, Object> request) {
    return runtime.setGlobal(id, request);
  }

  @PostMapping("/sessions/{id}/agenda/{group}/focus")
  public Map<String, Object> focus(
      @PathVariable("id") String id, @PathVariable("group") String group) {
    return runtime.focus(id, group);
  }

  @PostMapping("/sessions/{id}/queries")
  public Map<String, Object> query(
      @PathVariable("id") String id, @RequestBody Map<String, Object> request) {
    return runtime.query(id, request);
  }

  @GetMapping("/sessions/{id}/facts")
  public Map<String, Object> objects(@PathVariable("id") String id) {
    return runtime.objects(id);
  }

  @PostMapping("/sessions/{id}/clock/advance")
  public Map<String, Object> advanceClock(
      @PathVariable("id") String id, @RequestBody Map<String, Object> request) {
    return runtime.advanceClock(id, request);
  }

  @GetMapping("/sessions/{id}/owner")
  public Map<String, Object> owner(@PathVariable("id") String id) {
    return runtime.owner(id);
  }

  @DeleteMapping("/sessions/{id}")
  public Map<String, Object> close(@PathVariable("id") String id) {
    return runtime.close(id);
  }
}
