package com.example.drools.sdk.document.feign;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

public class RuleCenterFeignConfiguration {
  @Bean
  public feign.RequestInterceptor ruleCenterAuthentication(
      @Value("${drools.rule-center.username:}") String username,
      @Value("${drools.rule-center.password:}") String password) {
    return template -> {
      if (!username.isEmpty()) {
        String credentials = username + ":" + password;
        template.header(
            "Authorization",
            "Basic "
                + java.util.Base64.getEncoder()
                    .encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      }
    };
  }
}
