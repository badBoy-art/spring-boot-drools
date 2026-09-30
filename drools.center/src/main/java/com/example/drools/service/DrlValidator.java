package com.example.drools.service;

import org.kie.api.builder.Message;
import org.kie.api.io.ResourceType;
import org.kie.internal.utils.KieHelper;
import org.springframework.stereotype.Component;

/** Standalone DRL validation for form drafts; releases validate the complete native resource bundle. */
@Component
public class DrlValidator {
  public void validate(String source) {
    org.kie.api.builder.Results results =
        new KieHelper().addContent(source, ResourceType.DRL).verify();
    if (results.hasMessages(Message.Level.ERROR))
      throw new IllegalArgumentException(
          "DRL compilation failed: " + results.getMessages(Message.Level.ERROR));
  }
}
