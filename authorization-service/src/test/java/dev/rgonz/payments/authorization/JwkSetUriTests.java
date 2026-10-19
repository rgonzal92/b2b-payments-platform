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
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * In the cluster the Network's token names Keycloak's host address as its issuer, which the pods
 * can't reach: the service fetches the signing keys from {@code jwk-set-uri} instead, and still
 * checks {@code iss} against {@code issuer-uri}.
 */
class JwkSetUriTests {

  // An address nothing answers on: the service must never ask it for the discovery document.
  private static final String ISSUER = "http://keycloak.invalid:8180/realms/payments";

  private final Queue<String> requests = new ConcurrentLinkedQueue<>();
  private RSAKey key;
  private HttpServer keycloak;

  @BeforeEach
  void startKeycloak() throws Exception {
    key = new RSAKeyGenerator(2048).keyID("test-key").generate();
    var jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
    keycloak = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    keycloak.createContext(
        "/",
        exchange -> {
          requests.add(exchange.getRequestURI().getPath());
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
    var decoder = new SecurityConfig().jwtDecoder(ISSUER, certsUri());

    var jwt = decoder.decode(token(ISSUER));

    assertThat(jwt.getSubject()).isEqualTo("service-account-network");
    assertThat(requests).containsOnly("/realms/payments/protocol/openid-connect/certs");
  }

  @Test
  void aTokenFromAnotherIssuerIsRefused() {
    var decoder = new SecurityConfig().jwtDecoder(ISSUER, certsUri());

    assertThatExceptionOfType(JwtValidationException.class)
        .isThrownBy(() -> decoder.decode(token("http://elsewhere.invalid/realms/payments")))
        .withMessageContaining("iss");
  }

  private String certsUri() {
    return "http://127.0.0.1:"
        + keycloak.getAddress().getPort()
        + "/realms/payments/protocol/openid-connect/certs";
  }

  private String token(String issuer) {
    var claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject("service-account-network")
            .claim("roles", List.of("network"))
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
        .encode(JwtEncoderParameters.from(claims))
        .getTokenValue();
  }
}
