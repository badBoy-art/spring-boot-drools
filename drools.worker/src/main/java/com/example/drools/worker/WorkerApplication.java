package com.example.drools.worker;

import com.example.drools.sdk.document.DocumentRuleSdkConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import(DocumentRuleSdkConfiguration.class)
public class WorkerApplication {
  public static void main(String[] args) {
    SpringApplication.run(WorkerApplication.class, args);
  }
}
