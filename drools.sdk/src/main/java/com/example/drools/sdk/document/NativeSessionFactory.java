package com.example.drools.sdk.document;

import org.kie.api.KieBase;
import org.kie.api.runtime.*;

/** Native creation seam, including KieStoreServices/JPA/JTA-backed sessions. */
@FunctionalInterface
public interface NativeSessionFactory {
  KieSession create(
      String ruleType,
      KieBase base,
      KieSessionConfiguration configuration,
      Environment environment);
}
