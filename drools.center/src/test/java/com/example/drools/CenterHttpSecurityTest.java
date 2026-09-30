package com.example.drools;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.drools.release.RuleReleaseService;
import com.fasterxml.jackson.databind.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.*;

@SpringBootTest(
    classes = SpringBootDroolsApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:center-security;MODE=MySQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.sql.init.mode=never",
      "drools.security.author-username=author",
      "drools.security.author-password=test-author",
      "drools.security.runtime-username=business",
      "drools.security.runtime-password=test-runtime"
    })
@AutoConfigureMockMvc
class CenterHttpSecurityTest {
  @Autowired MockMvc mvc;
  @MockBean RuleReleaseService releases;

  private String credentials(String username, String password) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString(
                (username + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void runtimeAccountsCanFetchReleasesButCannotPublishAndAuthorsNeedCsrf() throws Exception {
    when(releases.current("A"))
        .thenReturn(new ObjectMapper().createObjectNode().put("ruleType", "A"));
    mvc.perform(get("/rule/type/runtime").param("ruleType", "A"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/rule/type/runtime")
                .param("ruleType", "A")
                .header("Authorization", credentials("business", "test-runtime")))
        .andExpect(status().isOk());
    mvc.perform(
            post("/rule/releases/A")
                .contentType("application/json")
                .content("{}")
                .header("Authorization", credentials("business", "test-runtime")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/rule/releases/A")
                .contentType("application/json")
                .content("{}")
                .header("Authorization", credentials("author", "test-author")))
        .andExpect(status().isForbidden());
    MvcResult csrf =
        mvc.perform(get("/rule/csrf").header("Authorization", credentials("author", "test-author")))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode token = new ObjectMapper().readTree(csrf.getResponse().getContentAsString());
    when(releases.publish("A", 0, 0, "test"))
        .thenReturn(new ObjectMapper().createObjectNode().put("releaseId", 1));
    mvc.perform(
            post("/rule/releases/A")
                .contentType("application/json")
                .content("{\"expectedRevision\":0,\"expectedDraftVersion\":0,\"remark\":\"test\"}")
                .header("Authorization", credentials("author", "test-author"))
                .header(token.path("headerName").asText(), token.path("token").asText())
                .cookie(csrf.getResponse().getCookies()))
        .andExpect(status().isOk());
    javax.servlet.http.Cookie cookie =
        java.util.Arrays.stream(csrf.getResponse().getCookies())
            .filter(c -> "XSRF-TOKEN".equals(c.getName()) && c.getMaxAge() != 0)
            .findFirst()
            .orElseThrow(AssertionError::new);
    MvcResult subsequent =
        mvc.perform(
                get("/rule/csrf")
                    .header("Authorization", credentials("author", "test-author"))
                    .cookie(cookie))
            .andExpect(status().isOk())
            .andReturn();
    org.junit.jupiter.api.Assertions.assertEquals(
        token.path("token").asText(),
        new ObjectMapper()
            .readTree(subsequent.getResponse().getContentAsString())
            .path("token")
            .asText());
    org.junit.jupiter.api.Assertions.assertTrue(
        java.util.Arrays.stream(subsequent.getResponse().getCookies())
            .noneMatch(c -> "XSRF-TOKEN".equals(c.getName()) && c.getMaxAge() == 0));
    mvc.perform(
            post("/rule/releases/A")
                .contentType("application/json")
                .content("{\"expectedRevision\":0,\"expectedDraftVersion\":0,\"remark\":\"test\"}")
                .header("Authorization", credentials("author", "test-author"))
                .header(token.path("headerName").asText(), token.path("token").asText())
                .cookie(cookie))
        .andExpect(status().isOk());
    verify(releases, times(2)).publish("A", 0, 0, "test");
  }
}
