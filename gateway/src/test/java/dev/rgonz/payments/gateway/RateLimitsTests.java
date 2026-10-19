package dev.rgonz.payments.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/**
 * Whose bucket a token draws on. Keycloak always names the client in {@code azp}, so the tokens
 * without one are built here: a key the resolver can't find would make RequestRateLimiter answer a
 * bare 403, so there must always be one.
 */
class RateLimitsTests {

  private final RateLimits limits = new RateLimits();

  @Test
  void aTokensBucketIsItsClient() {
    assertThat(keyFor(Map.of("azp", "acme-erp", "sub", "service-account"))).isEqualTo("acme-erp");
  }

  @Test
  void aTokenWithoutAClientDrawsOnItsSubjectsBucket() {
    assertThat(keyFor(Map.of("sub", "user-1"))).isEqualTo("sub:user-1");
  }

  @Test
  void aTokenWithNeitherDrawsOnOneSharedBucket() {
    assertThat(keyFor(Map.of("scope", "x"))).isNotBlank().isEqualTo(keyFor(Map.of("scope", "y")));
  }

  private String keyFor(Map<String, Object> claims) {
    var jwt =
        new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
    var exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/v1/invoices"))
            .mutate()
            .principal(Mono.just(new JwtAuthenticationToken(jwt)))
            .build();
    return limits.keycloakClient().resolve(exchange).block();
  }
}
