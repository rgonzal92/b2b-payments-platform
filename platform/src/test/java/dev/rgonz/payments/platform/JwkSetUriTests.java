package dev.rgonz.payments.platform;

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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * In the cluster a token names Keycloak's host address as its issuer, which the pods can't reach:
 * with {@code jwk-set-uri} set, Spring Boot's decoder fetches the signing keys from it and still
 * checks {@code iss} against {@code issuer-uri}, never asking the issuer for its discovery
 * document.
 */
class JwkSetUriTests {

  // An address nothing answers on.
  private static final String ISSUER = "http://keycloak.invalid:8180/realms/payments";

  private final Queue<String> requested = new ConcurrentLinkedQueue<>();
  private RSAKey signingKey;
  private HttpServer jwks;

  @BeforeEach
  void serveTheKeys() throws Exception {
    signingKey = new RSAKeyGenerator(2048).keyID("platform-test-key").generate();
    var body = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
    jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    jwks.createContext(
        "/",
        exchange -> {
          requested.add(exchange.getRequestURI().getPath());
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    jwks.start();
  }

  @AfterEach
  void stopServingTheKeys() {
    jwks.stop(0);
  }

  private final ApplicationContextRunner platform =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(OAuth2ResourceServerAutoConfiguration.class));

  @Test
  void aTokenFromTheIssuerIsAcceptedWithKeysFromTheJwkSetUri() {
    platform
        .withPropertyValues(issuerUri(), jwkSetUri())
        .run(
            context -> {
              var jwt = context.getBean(JwtDecoder.class).decode(signedBy(ISSUER));

              assertThat(jwt.getSubject()).isEqualTo("service-account-acme-erp");
              assertThat(requested).containsOnly("/certs");
            });
  }

  @Test
  void aTokenFromAnotherIssuerIsRefused() {
    platform
        .withPropertyValues(issuerUri(), jwkSetUri())
        .run(
            context ->
                assertThatExceptionOfType(JwtValidationException.class)
                    .isThrownBy(
                        () ->
                            context
                                .getBean(JwtDecoder.class)
                                .decode(signedBy("http://elsewhere.invalid/realms/payments")))
                    .withMessageContaining("iss"));
  }

  private static String issuerUri() {
    return "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ISSUER;
  }

  private String jwkSetUri() {
    return "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:"
        + jwks.getAddress().getPort()
        + "/certs";
  }

  private String signedBy(String issuer) {
    var claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject("service-account-acme-erp")
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)))
        .encode(JwtEncoderParameters.from(claims))
        .getTokenValue();
  }
}
