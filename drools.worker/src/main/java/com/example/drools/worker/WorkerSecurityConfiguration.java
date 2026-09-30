package com.example.drools.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@SuppressWarnings("deprecation")
public class WorkerSecurityConfiguration {
  @Bean
  public SecurityFilterChain workerSecurity(HttpSecurity http) throws Exception {
    http.csrf()
        .disable()
        .sessionManagement()
        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        .and()
        .authorizeRequests()
        .antMatchers("/actuator/health/**")
        .permitAll()
        .anyRequest()
        .authenticated()
        .and()
        .httpBasic();
    return http.build();
  }

  @Bean
  public UserDetailsService workerUsers(
      @Value("${drools.worker.username:}") String username,
      @Value("${drools.worker.password:}") String password) {
    if (username.trim().isEmpty() || password.trim().isEmpty())
      throw new IllegalStateException(
          "Configure non-empty drools.worker.username and drools.worker.password before starting"
              + " Worker");
    return new InMemoryUserDetailsManager(
        User.withUsername(username)
            .password(workerPasswordEncoder().encode(password))
            .roles("WORKER")
            .build());
  }

  @Bean
  public PasswordEncoder workerPasswordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
