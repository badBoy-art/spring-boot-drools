package com.example.drools.worker;

import com.example.drools.sdk.document.DroolsRuleCenterSdk;
import com.example.drools.sdk.document.ManagedKieSession;
import com.example.drools.sdk.document.RuleRuntimeOptions;
import com.example.drools.worker.WorkerStateRepository.WorkerSessionRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.PostConstruct;
import org.kie.api.definition.type.FactType;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.EntryPoint;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.api.runtime.rule.QueryResults;
import org.kie.api.runtime.rule.QueryResultsRow;
import org.kie.api.time.SessionPseudoClock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** HTTP-facing stateful runtime. A session is always served by exactly one lease owner. */
@Service
public class WorkerRuntimeService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(WorkerRuntimeService.class);
  private final DroolsRuleCenterSdk sdk;
  private final WorkerStateRepository repository;
  private final ObjectMapper mapper;
  private final String nodeId;
  private final String baseUrl;
  private final Set<String> allowedFactTypes;

  @Value("${drools.worker.durable-writes:true}")
  private boolean durableWrites = true;

  private final long checkpointMillis;
  private final ConcurrentMap<String, WorkerSessionRecord> localSessions =
      new ConcurrentHashMap<String, WorkerSessionRecord>();
  private final ConcurrentMap<String, Long> lastCheckpoint = new ConcurrentHashMap<String, Long>();

  public WorkerRuntimeService(
      DroolsRuleCenterSdk sdk,
      WorkerStateRepository repository,
      ObjectMapper mapper,
      @Value("${drools.session.node-id:${HOSTNAME:local}}") String nodeId,
      @Value("${drools.worker.base-url:http://localhost:8090}") String baseUrl,
      @Value("${drools.worker.allowed-fact-types:}") String allowedTypes,
      @Value("${drools.worker.checkpoint-ms:60000}") long checkpointMillis) {
    this.sdk = sdk;
    this.repository = repository;
    this.mapper = mapper;
    this.nodeId = nodeId;
    this.baseUrl = trimSlash(baseUrl);
    this.allowedFactTypes =
        allowedTypes == null || allowedTypes.trim().isEmpty()
            ? Collections.<String>emptySet()
            : new HashSet<String>(Arrays.asList(allowedTypes.split("\\s*,\\s*")));
    if (checkpointMillis <= 0)
      throw new IllegalArgumentException("drools.worker.checkpoint-ms must be positive");
    this.checkpointMillis = checkpointMillis;
  }

  @PostConstruct
  public void registerNode() {
    repository.heartbeat(nodeId, baseUrl);
  }

  public Map<String, Object> createSession(Map<String, Object> request) {
    String ruleType = requiredString(request, "ruleType");
    String clockType = optionalString(request, "clockType", "realtime");
    boolean active = booleanValue(request.get("active"), false);
    boolean timedAuto = booleanValue(request.get("timedRuleExecution"), false);
    RuleRuntimeOptions.Builder builder =
        RuleRuntimeOptions.builder()
            .sessionProperty("drools.clockType", clockType)
            .environment(
                "worker-json-globals-v1", org.kie.api.KieServices.Factory.get().newEnvironment());
    if (timedAuto) builder.sessionProperty("drools.timedRuleExecution", "YES");
    RuleRuntimeOptions options = builder.build();
    sdk.refresh(ruleType, options);
    String sessionId = sdk.newSession(ruleType, options);
    try {
      repository.registerSession(sessionId, ruleType, clockType, active, timedAuto);
      WorkerSessionRecord record =
          new WorkerSessionRecord(sessionId, ruleType, clockType, active, timedAuto);
      localSessions.put(sessionId, record);
      sdk.checkpointSession(
          sessionId); // baseline snapshot makes an unmodified session recoverable too
      lastCheckpoint.put(sessionId, System.currentTimeMillis());
      if (active) sdk.startFireUntilHalt(sessionId);
      Map<String, Object> response = new LinkedHashMap<String, Object>();
      response.put("sessionId", sessionId);
      response.put("ownerNode", nodeId);
      response.put("ownerUrl", baseUrl);
      response.put("ruleType", ruleType);
      response.put("active", active);
      response.put("clockType", clockType);
      return response;
    } catch (RuntimeException failure) {
      sdk.closeSession(sessionId);
      repository.closeSession(sessionId);
      try {
        sdk.deletePersistedSession(sessionId);
      } catch (RuntimeException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  public Map<String, Object> insert(String sessionId, Map<String, Object> request) {
    String type = requiredString(request, "type");
    Map<String, Object> values = objectMap(request.get("fact"), "fact");
    String entryPoint = optionalString(request, "entryPoint", null);
    AtomicReference<String> handle = new AtomicReference<String>();
    withSession(
        sessionId,
        session -> {
          Object fact = resolveFact(session, type, values);
          FactHandle inserted;
          if (entryPoint == null || entryPoint.trim().isEmpty()) inserted = session.insert(fact);
          else {
            EntryPoint point = session.getEntryPoint(entryPoint);
            if (point == null)
              throw new IllegalArgumentException("Unknown KIE entry point: " + entryPoint);
            inserted = point.insert(fact);
          }
          handle.set(inserted.toExternalForm());
        });
    Map<String, Object> response = new LinkedHashMap<String, Object>();
    response.put("factHandle", handle.get());
    return response;
  }

  public Map<String, Object> update(String sessionId, Map<String, Object> request) {
    String externalHandle = requiredString(request, "factHandle");
    String type = requiredString(request, "type");
    Map<String, Object> values = objectMap(request.get("fact"), "fact");
    withSession(
        sessionId,
        session -> {
          FactHandle handle = findHandle(session, externalHandle);
          findEntryPoint(session, externalHandle)
              .update(handle, resolveFact(session, type, values));
        });
    return Collections.<String, Object>singletonMap("updated", true);
  }

  public Map<String, Object> retract(String sessionId, String externalHandle) {
    withSession(
        sessionId,
        session ->
            findEntryPoint(session, externalHandle).delete(findHandle(session, externalHandle)));
    return Collections.<String, Object>singletonMap("retracted", true);
  }

  public Map<String, Object> fire(String sessionId, Map<String, Object> request) {
    if (requireLocal(sessionId).isFiring())
      throw new IllegalStateException(
          "Session is in active fireUntilHalt mode; halt it before fireAllRules");
    Integer max =
        request == null || request.get("max") == null
            ? null
            : Integer.valueOf(String.valueOf(request.get("max")));
    AtomicReference<Integer> fired = new AtomicReference<Integer>(0);
    withSession(
        sessionId,
        session -> fired.set(max == null ? session.fireAllRules() : session.fireAllRules(max)));
    return Collections.<String, Object>singletonMap("fired", fired.get());
  }

  public Map<String, Object> start(String sessionId) {
    requireLocal(sessionId);
    repository.setActive(sessionId, true);
    try {
      sdk.startFireUntilHalt(sessionId);
    } catch (RuntimeException failure) {
      repository.setActive(sessionId, false);
      throw failure;
    }
    return Collections.<String, Object>singletonMap("active", true);
  }

  public Map<String, Object> halt(String sessionId) {
    requireLocal(sessionId);
    sdk.halt(sessionId);
    repository.setActive(sessionId, false);
    return Collections.<String, Object>singletonMap("active", false);
  }

  public Map<String, Object> checkpoint(String sessionId) {
    requireLocal(sessionId);
    synchronized (requireLocal(sessionId)) {
      checkpointState(sessionId);
    }
    lastCheckpoint.put(sessionId, System.currentTimeMillis());
    return Collections.<String, Object>singletonMap("checkpointed", true);
  }

  public Map<String, Object> setGlobal(String sessionId, Map<String, Object> request) {
    String name = requiredString(request, "name");
    withSession(
        sessionId,
        session -> {
          session.setGlobal(name, request.get("value"));
          repository.saveGlobal(
              sessionId, name, mapper.valueToTree(request.get("value")).toString());
        });
    return Collections.<String, Object>singletonMap("set", name);
  }

  public Map<String, Object> focus(String sessionId, String group) {
    withSession(sessionId, session -> session.getAgenda().getAgendaGroup(group).setFocus());
    return Collections.<String, Object>singletonMap("focused", group);
  }

  public Map<String, Object> advanceClock(String sessionId, Map<String, Object> request) {
    long amount = Long.parseLong(requiredString(request, "amount"));
    String unitName =
        optionalString(request, "unit", "MILLISECONDS").toUpperCase(java.util.Locale.ROOT);
    if (amount < 0) throw new IllegalArgumentException("Time cannot move backwards");
    java.util.concurrent.TimeUnit unit = java.util.concurrent.TimeUnit.valueOf(unitName);
    withSession(
        sessionId,
        session -> {
          if (!(session.getSessionClock() instanceof SessionPseudoClock))
            throw new IllegalStateException(
                "Session clock is not pseudo; advanceClock is only valid for tests/simulations");
          ((SessionPseudoClock) session.getSessionClock()).advanceTime(amount, unit);
        });
    return Collections.<String, Object>singletonMap("advanced", amount + " " + unit.name());
  }

  public Map<String, Object> query(String sessionId, Map<String, Object> request) {
    String name = requiredString(request, "name");
    Object[] arguments =
        request.get("arguments") instanceof List
            ? ((List<?>) request.get("arguments")).toArray()
            : new Object[0];
    AtomicReference<List<Map<String, Object>>> rows =
        new AtomicReference<List<Map<String, Object>>>(new ArrayList<Map<String, Object>>());
    withSession(
        sessionId,
        session -> {
          QueryResults results = session.getQueryResults(name, arguments);
          List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
          String[] identifiers = results.getIdentifiers();
          for (QueryResultsRow row : results) {
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            for (String identifier : identifiers) value.put(identifier, row.get(identifier));
            values.add(value);
          }
          rows.set(values);
        },
        false);
    Map<String, Object> response = new LinkedHashMap<String, Object>();
    response.put("rows", rows.get());
    return response;
  }

  public Map<String, Object> objects(String sessionId) {
    AtomicReference<List<Map<String, Object>>> objects =
        new AtomicReference<List<Map<String, Object>>>(new ArrayList<Map<String, Object>>());
    withSession(
        sessionId,
        session -> {
          List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
          for (EntryPoint point : session.getEntryPoints())
            for (FactHandle handle : point.getFactHandles()) {
              Map<String, Object> item = new LinkedHashMap<String, Object>();
              item.put("factHandle", handle.toExternalForm());
              item.put("fact", point.getObject(handle));
              item.put("entryPoint", point.getEntryPointId());
              values.add(item);
            }
          objects.set(values);
        },
        false);
    Map<String, Object> response = new LinkedHashMap<String, Object>();
    response.put("facts", objects.get());
    return response;
  }

  public Map<String, Object> close(String sessionId) {
    requireLocal(sessionId);
    repository.closeSession(sessionId); // Mark closed while the owner lease is held.
    sdk.closeSession(sessionId);
    localSessions.remove(sessionId);
    lastCheckpoint.remove(sessionId);
    repository.closeSession(sessionId);
    sdk.deletePersistedSession(sessionId);
    return Collections.<String, Object>singletonMap("closed", true);
  }

  public Map<String, Object> owner(String sessionId) {
    String owner = sdk.sessionOwner(sessionId);
    String url =
        owner == null ? null : (owner.equals(nodeId) ? baseUrl : repository.nodeUrl(owner));
    Map<String, Object> response = new LinkedHashMap<String, Object>();
    response.put("sessionId", sessionId);
    response.put("ownerNode", owner);
    response.put("ownerUrl", url);
    return response;
  }

  @Scheduled(fixedDelayString = "${drools.worker.maintenance-ms:5000}")
  public void maintainSessions() {
    try {
      repository.heartbeat(nodeId, baseUrl);
    } catch (RuntimeException failure) {
      log.warn("Worker maintenance operation failed", failure);
    }
    for (WorkerSessionRecord record : repository.openSessions()) {
      if (localSessions.containsKey(record.sessionId)) {
        String currentOwner = sdk.sessionOwner(record.sessionId);
        if (!nodeId.equals(currentOwner)) {
          localSessions.remove(record.sessionId);
          lastCheckpoint.remove(record.sessionId);
          if (currentOwner != null) continue;
        } else {
          long now = System.currentTimeMillis();
          Long last = lastCheckpoint.get(record.sessionId);
          if (last == null || now - last >= checkpointMillis) {
            try {
              synchronized (sdk.session(record.sessionId)) {
                checkpointState(record.sessionId);
              }
              lastCheckpoint.put(record.sessionId, now);
            } catch (RuntimeException failure) {
              log.warn("Worker maintenance operation failed", failure);
            }
          }
          continue;
        }
      }
      String owner = sdk.sessionOwner(record.sessionId);
      if (owner != null && !owner.equals(nodeId)) continue;
      try {
        sdk.restoreSession(record.sessionId, record.ruleType, options(record));
        localSessions.put(record.sessionId, record);
        rebindGlobals(record.sessionId);
        if (record.active) sdk.startFireUntilHalt(record.sessionId);
        lastCheckpoint.put(record.sessionId, System.currentTimeMillis());
      } catch (RuntimeException unavailableOrInvalid) {
        if (sdk.sessionOwner(record.sessionId) == null)
          log.warn("Session recovery failed: {}", record.sessionId, unavailableOrInvalid);
      }
    }
  }

  /** JSON globals are external to native marshalling; freeze them with the session checkpoint. */
  private void checkpointState(String id) {
    ManagedKieSession managed = sdk.session(id);
    synchronized (managed) {
      boolean active = managed.isFiring();
      if (active) managed.halt();
      boolean completed = false;
      try {
        repository.transaction(
            () -> {
              for (String name : repository.globals(id).keySet()) {
                try {
                  repository.saveGlobal(
                      id, name, mapper.writeValueAsString(managed.kieSession().getGlobal(name)));
                } catch (Exception e) {
                  throw new IllegalStateException("Unable to checkpoint JSON global " + name, e);
                }
              }
              sdk.checkpointSession(id);
              return null;
            });
        completed = true;
      } finally {
        if (active && completed) {
          if (org.springframework.transaction.support.TransactionSynchronizationManager
              .isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                      @Override
                      public void afterCompletion(int status) {
                        if (status == STATUS_COMMITTED) restartAfterCheckpoint(id, managed);
                      }
                    });
          } else restartAfterCheckpoint(id, managed);
        }
      }
    }
  }

  private void restartAfterCheckpoint(String id, ManagedKieSession managed) {
    try {
      if (sdk.session(id) == managed) managed.startFireUntilHalt();
    } catch (RuntimeException failure) {
      log.warn("Unable to resume active session after checkpoint: {}", id, failure);
    }
  }

  private void rebindGlobals(String id) {
    KieSession session = sdk.session(id).kieSession();
    for (Map.Entry<String, String> entry : repository.globals(id).entrySet()) {
      try {
        session.setGlobal(entry.getKey(), mapper.readValue(entry.getValue(), Object.class));
      } catch (Exception e) {
        throw new IllegalStateException("Unable to restore JSON global " + entry.getKey(), e);
      }
    }
  }

  private RuleRuntimeOptions options(WorkerSessionRecord record) {
    RuleRuntimeOptions.Builder builder =
        RuleRuntimeOptions.builder().sessionProperty("drools.clockType", record.clockType);
    org.kie.api.runtime.Environment environment =
        org.kie.api.KieServices.Factory.get().newEnvironment();
    org.drools.core.base.MapGlobalResolver globals = new org.drools.core.base.MapGlobalResolver();
    for (Map.Entry<String, String> entry : repository.globals(record.sessionId).entrySet()) {
      try {
        globals.setGlobal(entry.getKey(), mapper.readValue(entry.getValue(), Object.class));
      } catch (Exception e) {
        throw new IllegalStateException("Invalid persisted JSON global " + entry.getKey(), e);
      }
    }
    environment.set(org.kie.api.runtime.EnvironmentName.GLOBALS, globals);
    builder.environment("worker-json-globals-v1", environment);
    if (record.timedAuto) builder.sessionProperty("drools.timedRuleExecution", "YES");
    return builder.build();
  }

  /** An optional idempotency key binds a successful response to the durable session checkpoint. */
  public Map<String, Object> idempotent(
      String id,
      String key,
      String kind,
      Map<String, Object> body,
      java.util.function.Supplier<Map<String, Object>> action) {
    if (key == null || key.trim().isEmpty()) return action.get();
    if (key.length() > 128) throw new IllegalArgumentException("Idempotency key is too long");
    ManagedKieSession managed = requireLocal(id);
    synchronized (managed) {
      try {
        String canonical =
            kind
                + mapper
                    .copy()
                    .configure(
                        com.fasterxml.jackson.databind.SerializationFeature
                            .ORDER_MAP_ENTRIES_BY_KEYS,
                        true)
                    .writeValueAsString(body);
        StringBuilder hash = new StringBuilder();
        for (byte value :
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
          hash.append(String.format("%02x", value & 255));
        return repository.transaction(
            () -> {
              Map<String, Object> previous = repository.command(id, key);
              if (previous != null) {
                if (!hash.toString().equals(String.valueOf(previous.get("request_hash"))))
                  throw new WorkerCommandConflictException(
                      "Idempotency key was used with a different command");
                try {
                  return mapper.readValue(String.valueOf(previous.get("response_json")), Map.class);
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              }
              Map<String, Object> response = action.get();
              try {
                repository.saveCommand(
                    id, key, hash.toString(), mapper.writeValueAsString(response));
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
              return response;
            });
      } catch (WorkerCommandConflictException conflict) {
        throw conflict;
      } catch (Exception failure) {
        sdk.closeSession(id);
        localSessions.remove(id);
        throw new IllegalStateException(
            "Command did not commit; retry with the same idempotency key", failure);
      }
    }
  }

  private void withSession(String sessionId, SessionAction action) {
    withSession(sessionId, action, true);
  }

  private void withSession(String sessionId, SessionAction action, boolean mutation) {
    ManagedKieSession managed = requireLocal(sessionId);
    synchronized (managed) {
      java.util.function.Supplier<Void> operation =
          () -> {
            KieSession session = managed.kieSession();
            if (managed.isFiring()) {
              AtomicReference<RuntimeException> failure = new AtomicReference<RuntimeException>();
              java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
              session.submit(
                  kie -> {
                    try {
                      action.run(kie);
                    } catch (RuntimeException e) {
                      failure.set(e);
                    } finally {
                      done.countDown();
                    }
                  });
              try {
                if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS))
                  throw new IllegalStateException("Native command did not finish");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
              if (failure.get() != null) throw failure.get();
            } else action.run(session);
            if (mutation && durableWrites) {
              checkpointState(sessionId);
              lastCheckpoint.put(sessionId, System.currentTimeMillis());
            }
            return null;
          };
      try {
        if (mutation && durableWrites) repository.transaction(operation);
        else operation.get();
      } catch (RuntimeException failure) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive()) {
          sdk.closeSession(sessionId);
          localSessions.remove(sessionId);
        }
        throw failure;
      }
    }
  }

  private ManagedKieSession requireLocal(String sessionId) {
    ManagedKieSession managed;
    try {
      managed = sdk.session(sessionId);
    } catch (IllegalArgumentException | IllegalStateException missing) {
      String owner = sdk.sessionOwner(sessionId);
      if (owner != null && !owner.equals(nodeId))
        throw new WorkerNotOwnerException(owner, repository.nodeUrl(owner));
      WorkerSessionRecord record = repository.findSession(sessionId);
      if (record == null) throw missing;
      try {
        sdk.restoreSession(sessionId, record.ruleType, options(record));
        localSessions.put(sessionId, record);
        rebindGlobals(sessionId);
        if (record.active) sdk.startFireUntilHalt(sessionId);
        managed = sdk.session(sessionId);
      } catch (IllegalStateException lostRace) {
        owner = sdk.sessionOwner(sessionId);
        if (owner != null && !owner.equals(nodeId))
          throw new WorkerNotOwnerException(owner, repository.nodeUrl(owner));
        throw lostRace;
      }
    }
    String owner = sdk.sessionOwner(sessionId);
    if (owner != null && !owner.equals(nodeId))
      throw new WorkerNotOwnerException(owner, repository.nodeUrl(owner));
    return managed;
  }

  private Object resolveFact(KieSession session, String typeName, Map<String, Object> values) {
    int split = typeName.lastIndexOf('.');
    String packageName = split < 0 ? "" : typeName.substring(0, split);
    String simpleName = split < 0 ? typeName : typeName.substring(split + 1);
    FactType declared = session.getKieBase().getFactType(packageName, simpleName);
    if (declared != null) {
      try {
        Object fact = declared.newInstance();
        for (Map.Entry<String, Object> field : values.entrySet()) {
          org.kie.api.definition.type.FactField definition = declared.getField(field.getKey());
          if (definition == null)
            throw new IllegalArgumentException("Unknown declared field: " + field.getKey());
          declared.set(
              fact, field.getKey(), mapper.convertValue(field.getValue(), definition.getType()));
        }
        return fact;
      } catch (Exception e) {
        throw new IllegalArgumentException("Unable to create declared fact " + typeName, e);
      }
    }
    if (!allowedFactTypes.contains(typeName))
      throw new IllegalArgumentException("Java fact type is not allowlisted: " + typeName);
    try {
      Class<?> factClass =
          Class.forName(
              typeName,
              true,
              ((org.drools.core.impl.InternalKnowledgeBase) session.getKieBase())
                  .getRootClassLoader());
      return mapper.convertValue(values, factClass);
    } catch (Exception e) {
      throw new IllegalArgumentException("Unable to map Java fact " + typeName, e);
    }
  }

  private EntryPoint findEntryPoint(KieSession session, String externalForm) {
    org.drools.core.common.DefaultFactHandle parsed =
        org.drools.core.common.DefaultFactHandle.createFromExternalFormat(externalForm);
    EntryPoint point = session.getEntryPoint(parsed.getEntryPointId().getEntryPointId());
    if (point == null) throw new IllegalArgumentException("Unknown entry point in fact handle");
    return point;
  }

  private FactHandle findHandle(KieSession session, String externalForm) {
    long id =
        org.drools.core.common.DefaultFactHandle.createFromExternalFormat(externalForm).getId();
    for (FactHandle handle : findEntryPoint(session, externalForm).getFactHandles())
      if (((org.drools.core.common.InternalFactHandle) handle).getId() == id) return handle;
    throw new IllegalArgumentException("Fact handle no longer exists");
  }

  private interface SessionAction {
    void run(KieSession session);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> objectMap(Object value, String name) {
    if (!(value instanceof Map))
      throw new IllegalArgumentException(name + " must be a JSON object");
    return (Map<String, Object>) value;
  }

  private String requiredString(Map<String, Object> request, String key) {
    String value =
        request == null || request.get(key) == null ? "" : String.valueOf(request.get(key)).trim();
    if (value.isEmpty()) throw new IllegalArgumentException(key + " is required");
    return value;
  }

  private String optionalString(Map<String, Object> request, String key, String fallback) {
    return request == null || request.get(key) == null
        ? fallback
        : String.valueOf(request.get(key));
  }

  private boolean booleanValue(Object value, boolean fallback) {
    return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
  }

  private static String trimSlash(String value) {
    return value == null ? "" : value.replaceAll("/+$", "");
  }
}
