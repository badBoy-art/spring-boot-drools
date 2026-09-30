package com.example.drools.security;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

/** Replace the UserDetailsService bean with the organization's identity provider when deploying. */
@Configuration
@SuppressWarnings("deprecation")
public class CenterSecurityConfiguration {
  @Bean
  public SecurityFilterChain centerSecurity(HttpSecurity http) throws Exception {
    http.csrf()
        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
        // There is no login session: Basic reauthenticates every request. Keep its CSRF cookie
        // stable.
        .sessionAuthenticationStrategy(
            new org.springframework.security.web.authentication.session
                .NullAuthenticatedSessionStrategy())
        .ignoringAntMatchers(
            "/rule/evaluate", "/rule/native/evaluate", "/rule/dmn/evaluate", "/rule/pmml/evaluate")
        .and()
        .sessionManagement()
        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        .and()
        .authorizeRequests()
        .antMatchers("/actuator/health/**")
        .permitAll()
        .antMatchers(
            "/rule/type/runtime",
            "/rule/evaluate",
            "/rule/native/evaluate",
            "/rule/dmn/evaluate",
            "/rule/pmml/evaluate")
        .hasAnyRole("EXECUTOR", "AUTHOR", "PUBLISHER")
        .antMatchers("/rule/publish/**", "/rule/native/publish/**", "/rule/status/**")
        .hasRole("PUBLISHER")
        .antMatchers(
            org.springframework.http.HttpMethod.POST,
            "/rule/releases/*",
            "/rule/releases/*/rollback")
        .hasRole("PUBLISHER")
        .antMatchers(org.springframework.http.HttpMethod.DELETE, "/rule/*")
        .hasRole("PUBLISHER")
        .anyRequest()
        .hasAnyRole("AUTHOR", "PUBLISHER")
        .and()
        .httpBasic();
    http.headers().contentTypeOptions();
    return http.build();
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
      UserDetailsService.class)
  public UserDetailsService centerUsers(
      @Value("${drools.security.author-username:}") String author,
      @Value("${drools.security.author-password:}") String authorPassword,
      @Value("${drools.security.runtime-username:}") String runtime,
      @Value("${drools.security.runtime-password:}") String runtimePassword) {
    if (author.trim().isEmpty()
        || authorPassword.trim().isEmpty()
        || runtime.trim().isEmpty()
        || runtimePassword.trim().isEmpty()
        || author.equals(runtime))
      throw new IllegalStateException(
          "Configure distinct author/runtime credentials before starting rule center");
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    return new InMemoryUserDetailsManager(
        User.withUsername(author)
            .password("{bcrypt}" + encoder.encode(authorPassword))
            .roles("AUTHOR", "PUBLISHER")
            .build(),
        User.withUsername(runtime)
            .password("{bcrypt}" + encoder.encode(runtimePassword))
            .roles("EXECUTOR")
            .build());
  }

  @RestController
  public static class CsrfEndpoint {
    @GetMapping("/rule/csrf")
    public Map<String, String> csrf(org.springframework.security.web.csrf.CsrfToken token) {
      Map<String, String> response = new LinkedHashMap<String, String>();
      response.put("headerName", token.getHeaderName());
      response.put("token", token.getToken());
      return response;
    }
  }
}
