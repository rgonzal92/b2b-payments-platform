package dev.rgonz.payments.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * In the cluster the Network's token names Keycloak's host address as its issuer, which the pods
 * can't reach: with {@code jwk-set-uri} set, the service's gRPC decoder (SecurityConfig) fetches
 * the signing keys from it and still checks {@code iss} against {@code issuer-uri}, never asking
 * the issuer for its discovery document.
 */
class JwkSetUriTests {

  // An address nothing answers on.
  private static final String ISSUER = "http://keycloak.invalid:8180/realms/payments";

  // SecurityConfig read from the properties, as the application reads it. Lazy, so only the decoder
  // is built: the gRPC and HTTP security beans need the whole application.
  private final ApplicationContextRunner app =
      new ApplicationContextRunner()
          .withBean(LazyInitializationBeanFactoryPostProcessor.class)
          .withUserConfiguration(SecurityConfig.class);

  private final Queue<String> requested = new ConcurrentLinkedQueue<>();
  private RSAKey signingKey;
  private HttpServer keycloak;

  @BeforeEach
  void startKeycloak() throws Exception {
    signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
    var jwks = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
    keycloak = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    keycloak.createContext(
        "/",
        exchange -> {
          requested.add(exchange.getRequestURI().getPath());
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, jwks.length);
          exchange.getResponseBody().write(jwks);
          exchange.close();
        });
    keycloak.start();
  }

  @AfterEach
  void stopKeycloak() {
    keycloak.stop(0);
  }

  @Test
  void aTokenFromTheIssuerIsAcceptedWithKeysFromTheJwkSetUri() {
    app.withPropertyValues(issuerUri(), jwkSetUri())
        .run(
            context -> {
              var jwt = context.getBean(JwtDecoder.class).decode(token(ISSUER));

              assertThat(jwt.getSubject()).isEqualTo("service-account-network");
              assertThat(requested).containsOnly("/realms/payments/protocol/openid-connect/certs");
            });
  }

  @Test
  void aTokenFromAnotherIssuerIsRefused() {
    app.withPropertyValues(issuerUri(), jwkSetUri())
        .run(
            context ->
                assertThatExceptionOfType(JwtValidationException.class)
                    .isThrownBy(
                        () ->
                            context
                                .getBean(JwtDecoder.class)
                                .decode(token("http://elsewhere.invalid/realms/payments")))
                    .withMessageContaining("iss"));
  }

  private static String issuerUri() {
    return "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ISSUER;
  }

  private String jwkSetUri() {
    return "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:"
        + keycloak.getAddress().getPort()
        + "/realms/payments/protocol/openid-connect/certs";
  }

  private String token(String issuer) {
    var claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject("service-account-network")
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)))
        .encode(JwtEncoderParameters.from(claims))
        .getTokenValue();
  }
}
