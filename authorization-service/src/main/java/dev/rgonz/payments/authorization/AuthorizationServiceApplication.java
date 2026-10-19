package dev.rgonz.payments.authorization;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Answers the Network's Authorizations without the platform (ADR-0016). So far it keeps its own
 * copy of every card it may authorize, fed by the platform's {@code cards} topic.
 */
@SpringBootApplication
public class AuthorizationServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(AuthorizationServiceApplication.class, args);
  }
}
