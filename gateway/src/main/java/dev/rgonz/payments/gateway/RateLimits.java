package dev.rgonz.payments.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Whose bucket a request takes a token from (M6 design, Rate limiting). The limits themselves are
 * the routes' RequestRateLimiter arguments in application.properties.
 */
@Configuration(proxyBeanMethods = false)
class RateLimits {

  /**
   * The Keycloak client the token was issued to (its {@code azp}), so every user of one client
   * shares its limit.
   */
  @Bean
  KeyResolver keycloakClient() {
    return exchange ->
        exchange
            .getPrincipal()
            .filter(JwtAuthenticationToken.class::isInstance)
            .map(principal -> ((JwtAuthenticationToken) principal).getToken())
            .mapNotNull(token -> token.getClaimAsString("azp"));
  }
}
