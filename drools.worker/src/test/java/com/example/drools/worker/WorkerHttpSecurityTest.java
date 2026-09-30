package com.example.drools.worker;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    classes = WorkerApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:worker-context;MODE=MySQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.sql.init.mode=always",
      "drools.rule-center.url=http://localhost",
      "drools.worker.username=worker-test",
      "drools.worker.password=worker-secret",
      "drools.session.node-id=worker-context",
      "drools.worker.base-url=http://worker-context:8090"
    })
@AutoConfigureMockMvc
class WorkerHttpSecurityTest {
  @Autowired private MockMvc mvc;

  @Test
  void requiresCredentialsForWorkerHttpApiButAllowsHealthProbe() throws Exception {
    mvc.perform(get("/worker/sessions/missing/owner")).andExpect(status().isUnauthorized());
    String credentials =
        Base64.getEncoder()
            .encodeToString("worker-test:worker-secret".getBytes(StandardCharsets.UTF_8));
    mvc.perform(
            get("/worker/sessions/missing/owner").header("Authorization", "Basic " + credentials))
        .andExpect(status().isOk());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
