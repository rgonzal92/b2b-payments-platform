package dev.rgonz.payments.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/**
 * Whose bucket a request takes a token from (M6 design, Rate limiting). The limits themselves are
 * the routes' RequestRateLimiter arguments in application.properties.
 */
@Configuration(proxyBeanMethods = false)
class RateLimits {

  /**
   * The Keycloak client the token was issued to (its {@code azp}), so every user of one client
   * shares its limit. The default: every route without its own resolver uses it.
   */
  @Bean
  @Primary
  KeyResolver keycloakClient() {
    return exchange ->
        exchange
            .getPrincipal()
            .filter(JwtAuthenticationToken.class::isInstance)
            .map(principal -> ((JwtAuthenticationToken) principal).getToken())
            .mapNotNull(token -> token.getClaimAsString("azp"));
  }

  /**
   * The address the connection came from, for the remittance link, which has no token. Forwarding
   * headers a client sends are not trusted: the Gateway strips them, and anyone could send a new
   * one with every request.
   */
  @Bean
  KeyResolver clientIp() {
    return exchange ->
        Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
            .map(address -> address.getAddress().getHostAddress());
  }
}
