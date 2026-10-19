package dev.rgonz.payments.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The edge in front of the platform's REST API (ADR-0017): routes {@code /v1} to the platform and
 * refuses a request without a valid Keycloak token before it gets there. The platform stays the
 * authority and keeps its own checks.
 */
@SpringBootApplication
public class GatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}
