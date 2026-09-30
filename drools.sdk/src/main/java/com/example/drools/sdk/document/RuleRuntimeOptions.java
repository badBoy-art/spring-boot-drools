package com.example.drools.sdk.document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.kie.api.conf.EventProcessingOption;
import org.kie.api.conf.KieBaseOption;
import org.kie.api.runtime.conf.KieSessionOption;
import org.kie.api.time.Calendar;

/**
 * Typed entry point for KIE configuration, with raw native properties for version-specific options.
 */
public final class RuleRuntimeOptions {
  private final EventProcessingOption eventProcessingMode;
  private final boolean eventModeConfigured;
  private final Map<String, String> baseProperties;
  private final Map<String, String> sessionProperties;
  private final Map<String, Calendar> calendars;
  private final Map<String, String> calendarVersions;
  private final org.kie.api.runtime.Environment environment;
  private final String environmentVersion;
  private final org.kie.api.marshalling.ObjectMarshallingStrategy[] marshallingStrategies;
  private final String marshallingVersion;
  private final NativeSessionFactory sessionFactory;
  private final List<KieBaseOption> baseOptions;
  private final List<KieSessionOption> sessionOptions;

  private RuleRuntimeOptions(Builder builder) {
    this.eventProcessingMode = builder.eventProcessingMode;
    this.eventModeConfigured = builder.eventModeConfigured;
    this.calendarVersions = immutableCopy(builder.calendarVersions);
    this.environment = builder.environment;
    this.environmentVersion = builder.environmentVersion;
    this.marshallingStrategies = builder.marshallingStrategies.clone();
    this.marshallingVersion = builder.marshallingVersion;
    this.sessionFactory = builder.sessionFactory;
    this.baseProperties = immutableCopy(builder.baseProperties);
    this.sessionProperties = immutableCopy(builder.sessionProperties);
    this.calendars =
        Collections.unmodifiableMap(new LinkedHashMap<String, Calendar>(builder.calendars));
    this.baseOptions =
        Collections.unmodifiableList(new ArrayList<KieBaseOption>(builder.baseOptions));
    this.sessionOptions =
        Collections.unmodifiableList(new ArrayList<KieSessionOption>(builder.sessionOptions));
  }

  public static Builder builder() {
    return new Builder();
  }

  public static RuleRuntimeOptions defaults() {
    return builder().build();
  }

  public boolean isEventModeConfigured() {
    return eventModeConfigured;
  }

  public EventProcessingOption getEventProcessingMode() {
    return eventProcessingMode;
  }

  public Map<String, String> getBaseProperties() {
    return baseProperties;
  }

  public Map<String, String> getSessionProperties() {
    return sessionProperties;
  }

  public Map<String, Calendar> getCalendars() {
    return calendars;
  }

  public Map<String, String> getCalendarVersions() {
    return calendarVersions;
  }

  public org.kie.api.runtime.Environment getEnvironment() {
    return environment;
  }

  public String getEnvironmentVersion() {
    return environmentVersion;
  }

  public org.kie.api.marshalling.ObjectMarshallingStrategy[] getMarshallingStrategies() {
    return marshallingStrategies.clone();
  }

  public String getMarshallingVersion() {
    return marshallingVersion;
  }

  public NativeSessionFactory getSessionFactory() {
    return sessionFactory;
  }

  public List<KieBaseOption> getBaseOptions() {
    return baseOptions;
  }

  public List<KieSessionOption> getSessionOptions() {
    return sessionOptions;
  }

  public Builder toBuilder() {
    Builder builder = new Builder();
    builder.eventProcessingMode = eventProcessingMode;
    builder.eventModeConfigured = eventModeConfigured;
    builder.baseProperties.putAll(baseProperties);
    builder.sessionProperties.putAll(sessionProperties);
    builder.calendars.putAll(calendars);
    builder.calendarVersions.putAll(calendarVersions);
    builder.environment = environment;
    builder.environmentVersion = environmentVersion;
    builder.marshallingStrategies = marshallingStrategies.clone();
    builder.marshallingVersion = marshallingVersion;
    builder.sessionFactory = sessionFactory;
    builder.baseOptions.addAll(baseOptions);
    builder.sessionOptions.addAll(sessionOptions);
    return builder;
  }

  private static Map<String, String> immutableCopy(Map<String, String> source) {
    return Collections.unmodifiableMap(new LinkedHashMap<String, String>(source));
  }

  public static final class Builder {
    private boolean eventModeConfigured;
    private EventProcessingOption eventProcessingMode = EventProcessingOption.STREAM;
    private final Map<String, String> baseProperties = new LinkedHashMap<String, String>();
    private final Map<String, String> sessionProperties = new LinkedHashMap<String, String>();
    private final Map<String, Calendar> calendars = new LinkedHashMap<String, Calendar>();
    private final Map<String, String> calendarVersions = new LinkedHashMap<String, String>();
    private org.kie.api.runtime.Environment environment;
    private String environmentVersion = "default";
    private org.kie.api.marshalling.ObjectMarshallingStrategy[] marshallingStrategies =
        new org.kie.api.marshalling.ObjectMarshallingStrategy[0];
    private String marshallingVersion = "default";
    private NativeSessionFactory sessionFactory =
        (type, base, config, env) -> base.newKieSession(config, env);
    private final List<KieBaseOption> baseOptions = new ArrayList<KieBaseOption>();
    private final List<KieSessionOption> sessionOptions = new ArrayList<KieSessionOption>();

    public Builder eventProcessingMode(EventProcessingOption mode) {
      if (mode == null) throw new IllegalArgumentException("event processing mode is required");
      this.eventProcessingMode = mode;
      this.eventModeConfigured = true;
      return this;
    }

    /** Pass through any KieBaseConfiguration property supported by the deployed Drools version. */
    public Builder baseProperty(String name, String value) {
      put(baseProperties, name, value);
      return this;
    }

    /** Pass through any KieSessionConfiguration property, including native timer options. */
    public Builder sessionProperty(String name, String value) {
      put(sessionProperties, name, value);
      return this;
    }

    public Builder baseOption(KieBaseOption option) {
      if (option == null) throw new IllegalArgumentException("base option is required");
      baseOptions.add(option);
      return this;
    }

    public Builder sessionOption(KieSessionOption option) {
      if (option == null) throw new IllegalArgumentException("session option is required");
      sessionOptions.add(option);
      return this;
    }

    public Builder calendar(String name, Calendar calendar) {
      if (name == null || name.trim().isEmpty() || calendar == null)
        throw new IllegalArgumentException("calendar name and instance are required");
      calendars.put(name, calendar);
      calendarVersions.put(name, calendar.getClass().getName());
      return this;
    }

    public Builder calendar(String name, String version, Calendar calendar) {
      calendar(name, calendar);
      put(calendarVersions, name, version);
      return this;
    }

    public Builder environment(String version, org.kie.api.runtime.Environment environment) {
      if (version == null || version.trim().isEmpty())
        throw new IllegalArgumentException("Environment version required");
      this.environment = environment;
      this.environmentVersion = version;
      return this;
    }

    public Builder marshalling(
        String version, org.kie.api.marshalling.ObjectMarshallingStrategy... strategies) {
      if (version == null || version.trim().isEmpty())
        throw new IllegalArgumentException("Marshalling version required");
      this.marshallingVersion = version;
      this.marshallingStrategies = strategies.clone();
      return this;
    }

    public Builder sessionFactory(NativeSessionFactory factory) {
      if (factory == null) throw new IllegalArgumentException("Session factory required");
      this.sessionFactory = factory;
      return this;
    }

    public RuleRuntimeOptions build() {
      return new RuleRuntimeOptions(this);
    }

    private static void put(Map<String, String> target, String name, String value) {
      if (name == null || name.trim().isEmpty() || value == null)
        throw new IllegalArgumentException("KIE property name/value are required");
      target.put(name, value);
    }
  }
}
