package com.example.drools.sdk.document;

import com.example.drools.sdk.document.feign.RuleTypeConfigFeignClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.kie.api.KieBase;
import org.kie.api.KieBaseConfiguration;
import org.kie.api.KieServices;
import org.kie.api.marshalling.Marshaller;
import org.kie.api.runtime.Environment;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.time.Calendar;

/** Loads published native DRL and owns stateful KIE sessions in the consuming business process. */
public final class DroolsRuleCenterSdk implements AutoCloseable {
  private final RuleTypeConfigFeignClient client;
  private final KieSessionStore sessionStore;
  private final KieSessionLifecycle lifecycle;
  private final KieSessionCoordinator coordinator;
  private final String nodeId;
  private final long leaseMillis;
  private final ScheduledExecutorService leaseRenewer;
  private final long maxSnapshotBytes;
  private final KieSessionSnapshotObserver snapshotObserver;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ConcurrentMap<String, RuntimeGeneration> generations =
      new ConcurrentHashMap<String, RuntimeGeneration>();
  private final ConcurrentMap<String, ManagedKieSession> sessions =
      new ConcurrentHashMap<String, ManagedKieSession>();
  private final ConcurrentMap<String, RuntimeGeneration> sessionGenerations =
      new ConcurrentHashMap<String, RuntimeGeneration>();
  private final ConcurrentMap<String, RuleRuntimeOptions> sessionOptions =
      new ConcurrentHashMap<String, RuleRuntimeOptions>();
  private final ConcurrentMap<String, KieSessionLease> sessionLeases =
      new ConcurrentHashMap<String, KieSessionLease>();
  private final ConcurrentMap<String, JsonNode> bundles = new ConcurrentHashMap<String, JsonNode>();
  private volatile boolean closed;

  public DroolsRuleCenterSdk(RuleTypeConfigFeignClient client) {
    this(client, null, null);
  }

  public DroolsRuleCenterSdk(RuleTypeConfigFeignClient client, KieSessionStore sessionStore) {
    this(client, sessionStore, null);
  }

  public DroolsRuleCenterSdk(
      RuleTypeConfigFeignClient client,
      KieSessionStore sessionStore,
      KieSessionLifecycle lifecycle) {
    this(client, sessionStore, lifecycle, null, "local-" + UUID.randomUUID().toString(), 30000L);
  }

  public DroolsRuleCenterSdk(
      RuleTypeConfigFeignClient client,
      KieSessionStore sessionStore,
      KieSessionLifecycle lifecycle,
      KieSessionCoordinator coordinator,
      String nodeId,
      long leaseMillis) {
    this(
        client,
        sessionStore,
        lifecycle,
        coordinator,
        nodeId,
        leaseMillis,
        64L * 1024L * 1024L,
        null);
  }

  public DroolsRuleCenterSdk(
      RuleTypeConfigFeignClient client,
      KieSessionStore sessionStore,
      KieSessionLifecycle lifecycle,
      KieSessionCoordinator coordinator,
      String nodeId,
      long leaseMillis,
      long maxSnapshotBytes,
      KieSessionSnapshotObserver snapshotObserver) {
    if (nodeId == null || nodeId.trim().isEmpty() || leaseMillis <= 0 || maxSnapshotBytes <= 0)
      throw new IllegalArgumentException("nodeId and positive lease/snapshot limits are required");
    this.client = client;
    this.sessionStore = sessionStore;
    this.lifecycle = lifecycle;
    this.coordinator = coordinator;
    this.nodeId = nodeId;
    this.leaseMillis = leaseMillis;
    this.maxSnapshotBytes = maxSnapshotBytes;
    this.snapshotObserver = snapshotObserver;
    if (coordinator == null) this.leaseRenewer = null;
    else {
      this.leaseRenewer =
          Executors.newSingleThreadScheduledExecutor(
              task -> {
                Thread thread = new Thread(task, "drools-session-lease-" + nodeId);
                thread.setDaemon(true);
                return thread;
              });
      long interval = Math.max(100L, leaseMillis / 3L);
      this.leaseRenewer.scheduleWithFixedDelay(
          new Runnable() {
            @Override
            public void run() {
              renewLeases();
            }
          },
          interval,
          interval,
          TimeUnit.MILLISECONDS);
    }
  }

  public synchronized JsonNode refresh(String ruleType) {
    return refresh(ruleType, RuleRuntimeOptions.defaults());
  }

  /**
   * Fetch and compile a new generation; existing sessions stay pinned to their original generation.
   */
  public synchronized JsonNode refresh(String ruleType, RuleRuntimeOptions options) {
    ensureOpen();
    JsonNode bundle = client.getRuntimeRuleType(ruleType);
    options = resolveOptions(bundle, options);
    RuntimeGeneration loaded = generations.get(ruleType);
    if (loaded != null && loaded.baseFingerprint.equals(baseFingerprint(bundle, options))) {
      bundles.put(ruleType, bundle);
      return bundle;
    }
    RuntimeGeneration generation = compile(bundle, options);
    RuntimeGeneration previous = generations.put(ruleType, generation);
    bundles.put(ruleType, bundle);
    if (previous != null) previous.retire();
    return bundle;
  }

  public JsonNode getRuleTypeConfig(String ruleType) {
    JsonNode bundle = bundles.get(ruleType);
    return bundle == null ? refresh(ruleType) : bundle;
  }

  public String newSession(String ruleType) {
    RuntimeGeneration loaded = generations.get(ruleType);
    return newSession(ruleType, loaded == null ? RuleRuntimeOptions.defaults() : loaded.options);
  }

  /** Backward-compatible convenience overload for realtime/pseudo clock selection. */
  public String newSession(String ruleType, String clockType) {
    RuntimeGeneration loaded = generations.get(ruleType);
    RuleRuntimeOptions.Builder options =
        loaded == null ? RuleRuntimeOptions.builder() : loaded.options.toBuilder();
    if (clockType != null && !clockType.trim().isEmpty())
      options.sessionProperty("drools.clockType", clockType);
    return newSession(ruleType, options.build());
  }

  public synchronized String newSession(String ruleType, RuleRuntimeOptions options) {
    ensureOpen();
    RuntimeGeneration generation = generations.get(ruleType);
    if (generation == null) {
      refresh(ruleType, options);
      generation = generations.get(ruleType);
    }
    options = resolveOptions(bundles.get(ruleType), options);
    if (!generation.baseFingerprint.equals(baseFingerprint(bundles.get(ruleType), options)))
      throw new IllegalStateException(
          "Rule type is loaded with different runtime options; call refresh(ruleType, options)"
              + " first");
    generation.acquire();
    String id = UUID.randomUUID().toString();
    KieSessionLease lease;
    try {
      lease = acquireLease(id);
    } catch (RuntimeException e) {
      generation.release();
      throw e;
    }
    if (coordinator != null && lease == null) {
      generation.release();
      throw new IllegalStateException("Unable to acquire KieSession owner lease");
    }
    KieSession created = null;
    try {
      created =
          options
              .getSessionFactory()
              .create(
                  ruleType,
                  generation.base,
                  sessionConfiguration(options),
                  options.getEnvironment());
      registerCalendars(created, options);
      if (lifecycle != null) lifecycle.onCreate(id, ruleType, created);
      ensureLeaseStillValid(lease);
      attachSession(id, generation, created, options, lease);
      return id;
    } catch (RuntimeException e) {
      if (created != null) created.dispose();
      generation.release();
      releaseLease(lease);
      throw e;
    }
  }

  public ManagedKieSession session(String sessionId) {
    ManagedKieSession session = sessions.get(sessionId);
    if (session == null)
      throw new IllegalArgumentException("Unknown or closed KieSession: " + sessionId);
    assertLease(sessionId);
    return session;
  }

  public void startFireUntilHalt(String sessionId) {
    session(sessionId).startFireUntilHalt();
  }

  public void halt(String sessionId) {
    session(sessionId).halt();
  }

  public void closeSession(String sessionId) {
    ManagedKieSession session = sessions.remove(sessionId);
    sessionGenerations.remove(sessionId);
    sessionOptions.remove(sessionId);
    if (session != null) session.close();
  }

  /**
   * Persist a native KIE snapshot. If active, fireUntilHalt is paused for the snapshot then
   * resumed. Business code must quiesce its own concurrent fact mutations while calling this
   * method.
   */
  public void checkpointSession(String sessionId) {
    requireStore();
    ManagedKieSession managed = session(sessionId);
    RuntimeGeneration generation = sessionGenerations.get(sessionId);
    KieSessionLease lease = sessionLeases.get(sessionId);
    synchronized (managed) {
      boolean wasFiring = managed.isFiring();
      long startedAt = System.nanoTime();
      boolean halted = false;
      try {
        if (wasFiring) {
          managed.halt();
          halted = true;
        }
        ByteArrayOutputStream bytes = new BoundedSnapshotStream(maxSnapshotBytes);
        generation.marshaller().marshall(bytes, managed.kieSession());
        if (bytes.size() > maxSnapshotBytes)
          throw new IllegalStateException(
              "KieSession snapshot exceeds configured limit: "
                  + bytes.size()
                  + " > "
                  + maxSnapshotBytes);
        assertLease(sessionId);
        sessionStore.save(
            new StoredKieSession(
                sessionId,
                generation.ruleType,
                sessionFingerprint(generation, sessionOptions.get(sessionId)),
                generation.bundle.toString(),
                bytes.toByteArray(),
                lease == null ? null : lease.getOwnerId(),
                lease == null ? 0L : lease.getFencingToken()));
        if (snapshotObserver != null)
          safeObserveCheckpoint(sessionId, bytes.size(), System.nanoTime() - startedAt);
      } catch (Exception e) {
        if (wasFiring && !halted) loseLease(sessionId);
        throw new IllegalStateException("Unable to checkpoint KieSession " + sessionId, e);
      } finally {
        if (wasFiring && halted && sessions.get(sessionId) == managed) managed.startFireUntilHalt();
      }
    }
  }

  /** Restore a checkpoint only with the exact DRL and runtime config that created it. */
  public synchronized String restoreSession(
      String sessionId, String ruleType, RuleRuntimeOptions options) {
    ensureOpen();
    requireStore();
    if (sessions.containsKey(sessionId))
      throw new IllegalArgumentException("KieSession is already open: " + sessionId);
    KieSessionLease lease = acquireLease(sessionId);
    if (coordinator != null && lease == null)
      throw new IllegalStateException(
          "KieSession is owned by node " + coordinator.ownerOf(sessionId));
    RuntimeGeneration generation = null;
    boolean archivedGeneration = false;
    boolean acquired = false;
    boolean attached = false;
    KieSession restored = null;
    long restoreStartedAt = System.nanoTime();
    try {
      StoredKieSession stored = sessionStore.load(sessionId);
      if (stored == null)
        throw new IllegalArgumentException("No persisted KieSession: " + sessionId);
      if (!ruleType.equals(stored.getRuleType()))
        throw new IllegalStateException(
            "Persisted session belongs to rule type " + stored.getRuleType());
      JsonNode archivedBundle = objectMapper.readTree(stored.getRuleBundle());
      options = resolveOptions(archivedBundle, options);
      if (!ruleType.equals(archivedBundle.path("ruleType").asText()))
        throw new IllegalStateException(
            "Persisted rule bundle type does not match snapshot metadata");
      generation = generations.get(ruleType);
      archivedGeneration =
          generation == null
              || !generation.baseFingerprint.equals(baseFingerprint(archivedBundle, options));
      if (archivedGeneration) generation = compile(archivedBundle, options);
      if (!generation.baseFingerprint.equals(baseFingerprint(archivedBundle, options))
          || !sessionFingerprint(generation, options).equals(stored.getRuleFingerprint()))
        throw new IllegalStateException(
            "Persisted session rule/config fingerprint differs from snapshot bundle or session"
                + " options");
      generation.acquire();
      acquired = true;
      KieSessionConfiguration configuration = sessionConfiguration(options);
      Environment environment =
          options.getEnvironment() == null
              ? KieServices.Factory.get().newEnvironment()
              : options.getEnvironment();
      restored =
          generation
              .marshaller()
              .unmarshall(
                  new ByteArrayInputStream(stored.getPayload()), configuration, environment);
      registerCalendars(restored, options);
      if (lifecycle != null) lifecycle.onRestore(sessionId, ruleType, restored);
      ensureLeaseStillValid(lease);
      attachSession(sessionId, generation, restored, options, lease);
      attached = true;
      if (snapshotObserver != null)
        safeObserveRestore(
            sessionId, stored.getPayload().length, System.nanoTime() - restoreStartedAt);
      if (archivedGeneration) generation.retire();
      return sessionId;
    } catch (Exception e) {
      if (!attached && restored != null) restored.dispose();
      if (acquired && generation != null) generation.release();
      if (archivedGeneration && generation != null) generation.retire();
      if (!attached) releaseLease(lease);
      if (e instanceof RuntimeException) throw (RuntimeException) e;
      throw new IllegalStateException("Unable to restore KieSession " + sessionId, e);
    }
  }

  public void deletePersistedSession(String sessionId) {
    requireStore();
    KieSessionLease lease = acquireLease(sessionId);
    if (coordinator != null && lease == null)
      throw new IllegalStateException(
          "Cannot delete a persisted session while node "
              + coordinator.ownerOf(sessionId)
              + " owns it");
    try {
      if (lease == null) sessionStore.delete(sessionId);
      else {
        if (!coordinator.renew(lease, leaseMillis))
          throw new IllegalStateException("Unable to validate delete lease for " + sessionId);
        sessionStore.delete(sessionId, lease);
      }
    } finally {
      if (lease != null) {
        coordinator.release(lease);
        coordinator.deleteIfUnowned(sessionId);
      }
    }
  }

  /**
   * Resolve the current live owner so an ingress/router can send requests to the right business
   * node.
   */
  public String sessionOwner(String sessionId) {
    if (coordinator == null) return sessions.containsKey(sessionId) ? nodeId : null;
    return coordinator.ownerOf(sessionId);
  }

  public String nodeId() {
    return nodeId;
  }

  private RuntimeGeneration compile(JsonNode bundle, RuleRuntimeOptions options) {
    KieServices services = KieServices.Factory.get();
    KieBaseConfiguration configuration = baseConfiguration(options);
    com.example.drools.runtime.CompiledRuleBundle compiled =
        new com.example.drools.runtime.RuleBundleCompiler().compile(bundle, configuration);
    return new RuntimeGeneration(
        compiled.base(),
        compiled.container(),
        bundle.path("ruleType").asText(),
        baseFingerprint(bundle, options),
        options,
        bundle.deepCopy());
  }

  private RuleRuntimeOptions resolveOptions(JsonNode bundle, RuleRuntimeOptions options) {
    if (!options.isEventModeConfigured() && bundle.hasNonNull("eventProcessingMode"))
      return options.toBuilder()
          .eventProcessingMode(
              org.kie.api.conf.EventProcessingOption.valueOf(
                  bundle.get("eventProcessingMode").asText().toUpperCase(java.util.Locale.ROOT)))
          .build();
    return options;
  }

  private KieBaseConfiguration baseConfiguration(RuleRuntimeOptions options) {
    KieBaseConfiguration configuration = KieServices.Factory.get().newKieBaseConfiguration();
    configuration.setOption(options.getEventProcessingMode());
    for (org.kie.api.conf.KieBaseOption option : options.getBaseOptions())
      configuration.setOption(option);
    for (Map.Entry<String, String> property : options.getBaseProperties().entrySet())
      configuration.setProperty(property.getKey(), property.getValue());
    return configuration;
  }

  /**
   * Full native container access: named bases/sessions, stateless sessions, pools, version updates
   * and scanners.
   */
  public synchronized org.kie.api.runtime.KieContainer kieContainer(String ruleType) {
    ensureOpen();
    if (!generations.containsKey(ruleType)) refresh(ruleType);
    return generations.get(ruleType).container;
  }

  public synchronized org.kie.api.KieBase kieBase(String ruleType) {
    kieContainer(ruleType);
    return generations.get(ruleType).base;
  }

  public Map<String, Object> evaluate(String ruleType, Map<String, Object> data) {
    refresh(ruleType);
    String id = newSession(ruleType);
    try {
      RuntimeGeneration generation = sessionGenerations.get(id);
      KieSession nativeSession = session(id).kieSession();
      com.example.drools.runtime.FrozenDocumentRuntime documentRuntime =
          new com.example.drools.runtime.FrozenDocumentRuntime();
      com.example.drools.domain.DocFact fact =
          documentRuntime.insert(generation.bundle, data, nativeSession);
      return documentRuntime.result(generation.bundle, fact, nativeSession.fireAllRules());
    } finally {
      closeSession(id);
    }
  }

  public synchronized NativeRuntimeLease retainRuntime(String ruleType) {
    kieContainer(ruleType);
    RuntimeGeneration generation = generations.get(ruleType);
    generation.acquire();
    return new NativeRuntimeLease(generation.container, generation.base, generation::release);
  }

  public <T> T runtime(String ruleType, Class<T> runtimeType) {
    return org.kie.api.runtime.KieRuntimeFactory.of(kieBase(ruleType)).get(runtimeType);
  }

  private KieSessionConfiguration sessionConfiguration(RuleRuntimeOptions options) {
    KieSessionConfiguration configuration = KieServices.Factory.get().newKieSessionConfiguration();
    for (org.kie.api.runtime.conf.KieSessionOption option : options.getSessionOptions())
      configuration.setOption(option);
    for (Map.Entry<String, String> property : options.getSessionProperties().entrySet())
      configuration.setProperty(property.getKey(), property.getValue());
    return configuration;
  }

  private void registerCalendars(KieSession session, RuleRuntimeOptions options) {
    for (Map.Entry<String, Calendar> calendar : options.getCalendars().entrySet())
      session.getCalendars().set(calendar.getKey(), calendar.getValue());
  }

  private void attachSession(
      final String id,
      final RuntimeGeneration generation,
      KieSession session,
      RuleRuntimeOptions options,
      final KieSessionLease lease) {
    sessionGenerations.put(id, generation);
    sessionOptions.put(id, options);
    if (lease != null) sessionLeases.put(id, lease);
    sessions.put(
        id,
        new ManagedKieSession(
            session,
            id,
            new Runnable() {
              @Override
              public void run() {
                sessionGenerations.remove(id);
                sessionOptions.remove(id);
                sessionLeases.remove(id);
                releaseLease(lease);
                generation.release();
              }
            }));
  }

  private KieSessionLease acquireLease(String sessionId) {
    return coordinator == null ? null : coordinator.acquire(sessionId, nodeId, leaseMillis);
  }

  private void releaseLease(KieSessionLease lease) {
    if (coordinator != null && lease != null) coordinator.release(lease);
  }

  private void assertLease(String sessionId) {
    KieSessionLease lease = sessionLeases.get(sessionId);
    if (coordinator != null && (lease == null || !coordinator.renew(lease, leaseMillis))) {
      loseLease(sessionId);
      throw new IllegalStateException("KieSession owner lease was lost: " + sessionId);
    }
  }

  private void ensureLeaseStillValid(KieSessionLease lease) {
    if (coordinator != null && (lease == null || !coordinator.renew(lease, leaseMillis)))
      throw new IllegalStateException("KieSession owner lease expired during initialization");
  }

  private void renewLeases() {
    if (closed || coordinator == null) return;
    for (Map.Entry<String, KieSessionLease> entry : sessionLeases.entrySet()) {
      try {
        if (!coordinator.renew(entry.getValue(), leaseMillis)) loseLease(entry.getKey());
      } catch (RuntimeException unavailable) {
        loseLease(entry.getKey());
      }
    }
  }

  private void loseLease(String sessionId) {
    ManagedKieSession session = sessions.remove(sessionId);
    sessionGenerations.remove(sessionId);
    sessionOptions.remove(sessionId);
    sessionLeases.remove(sessionId);
    if (session != null)
      session.close(); // a stale fencing token cannot release a new owner's lease
    if (snapshotObserver != null)
      try {
        snapshotObserver.onLeaseLost(sessionId, nodeId);
      } catch (RuntimeException ignored) {
      }
  }

  private void safeObserveCheckpoint(String sessionId, long bytes, long elapsed) {
    try {
      snapshotObserver.onCheckpoint(sessionId, bytes, elapsed);
    } catch (RuntimeException ignored) {
    }
  }

  private void safeObserveRestore(String sessionId, long bytes, long elapsed) {
    try {
      snapshotObserver.onRestore(sessionId, bytes, elapsed);
    } catch (RuntimeException ignored) {
    }
  }

  private String baseFingerprint(JsonNode bundle, RuleRuntimeOptions options) {
    com.fasterxml.jackson.databind.node.ObjectNode assets = objectMapper.createObjectNode();
    for (String key :
        new String[] {
          "ruleType",
          "docCode",
          "releaseId",
          "rules",
          "resources",
          "kjarBase64",
          "dependencyKjars",
          "artifact",
          "kmoduleXml",
          "pomXml",
          "kieBaseName",
          "executableModel",
          "documentFields",
          "documentObjects",
          "outputFields"
        }) if (bundle.has(key)) assets.set(key, bundle.get(key));
    StringBuilder canonical =
        new StringBuilder(assets.toString())
            .append('\n')
            .append(options.getEventProcessingMode().getMode());
    appendSorted(canonical, options.getBaseProperties());
    KieBaseConfiguration configuration = baseConfiguration(options);
    for (org.kie.api.conf.KieBaseOption option : options.getBaseOptions())
      canonical
          .append("\noption:")
          .append(option.getPropertyName())
          .append(':')
          .append(
              configuration.getProperty(option.getPropertyName()) == null
                  ? option.toString()
                  : configuration.getProperty(option.getPropertyName()));
    return sha256(canonical);
  }

  private String sessionFingerprint(RuntimeGeneration generation, RuleRuntimeOptions options) {
    StringBuilder canonical = new StringBuilder(generation.baseFingerprint);
    appendSorted(canonical, options.getSessionProperties());
    KieSessionConfiguration configuration = sessionConfiguration(options);
    for (org.kie.api.runtime.conf.KieSessionOption option : options.getSessionOptions())
      canonical
          .append("\noption:")
          .append(option.getPropertyName())
          .append(':')
          .append(
              configuration.getProperty(option.getPropertyName()) == null
                  ? option.toString()
                  : configuration.getProperty(option.getPropertyName()));
    appendSorted(canonical, options.getCalendarVersions());
    canonical
        .append("\nmarshalling:")
        .append(options.getMarshallingVersion())
        .append("\nenvironment:")
        .append(options.getEnvironmentVersion());
    return sha256(canonical);
  }

  private String sha256(StringBuilder canonical) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : hash) hex.append(String.format("%02x", b & 0xff));
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private void appendSorted(StringBuilder target, Map<String, String> values) {
    for (Map.Entry<String, String> entry : new TreeMap<String, String>(values).entrySet())
      target.append('\n').append(entry.getKey()).append('=').append(entry.getValue());
  }

  private void requireStore() {
    if (sessionStore == null) throw new IllegalStateException("No KieSessionStore configured");
  }

  private void ensureOpen() {
    if (closed) throw new IllegalStateException("DroolsRuleCenterSdk is closed");
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    if (leaseRenewer != null) leaseRenewer.shutdownNow();
    for (String id : sessions.keySet()) closeSession(id);
    for (RuntimeGeneration generation : generations.values()) generation.retire();
    generations.clear();
    bundles.clear();
    sessionLeases.clear();
  }

  private static final class BoundedSnapshotStream extends ByteArrayOutputStream {
    private final long limit;

    BoundedSnapshotStream(long limit) {
      this.limit = limit;
    }

    private void check(int length) {
      if ((long) count + length > limit)
        throw new IllegalStateException("KieSession snapshot exceeds configured limit");
    }

    @Override
    public synchronized void write(int value) {
      check(1);
      super.write(value);
    }

    @Override
    public synchronized void write(byte[] value, int offset, int length) {
      check(length);
      super.write(value, offset, length);
    }
  }

  private static final class RuntimeGeneration {
    private final KieBase base;
    private final org.kie.api.runtime.KieContainer container;
    private final String ruleType;
    private final String baseFingerprint;
    private final RuleRuntimeOptions options;
    private final JsonNode bundle;
    private int sessions;
    private boolean retired;
    private boolean disposed;

    private RuntimeGeneration(
        KieBase base,
        org.kie.api.runtime.KieContainer container,
        String ruleType,
        String fingerprint,
        RuleRuntimeOptions options,
        JsonNode bundle) {
      this.base = base;
      this.container = container;
      this.ruleType = ruleType;
      this.baseFingerprint = fingerprint;
      this.options = options;
      this.bundle = bundle;
    }

    private Marshaller marshaller() {
      return options.getMarshallingStrategies().length == 0
          ? KieServices.Factory.get().getMarshallers().newMarshaller(base)
          : KieServices.Factory.get()
              .getMarshallers()
              .newMarshaller(base, options.getMarshallingStrategies());
    }

    private synchronized void acquire() {
      if (retired) throw new IllegalStateException("Rule generation has been retired");
      sessions++;
    }

    private synchronized void release() {
      if (sessions > 0) sessions--;
      disposeIfUnused();
    }

    private synchronized void retire() {
      retired = true;
      disposeIfUnused();
    }

    private void disposeIfUnused() {
      if (retired && sessions == 0 && !disposed) {
        disposed = true;
        container.dispose();
      }
    }
  }
}
