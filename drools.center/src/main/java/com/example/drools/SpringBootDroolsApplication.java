package com.example.drools;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SpringBootDroolsApplication {

  public static void main(String[] args) {
    SpringApplication.run(SpringBootDroolsApplication.class, args);
  }
}
