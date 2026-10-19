package dev.rgonz.payments.gateway;

import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import reactor.core.publisher.Mono;

/**
 * Refuses a request without a valid Keycloak token before it reaches the platform, so a flood
 * without one stops here. Only the token's validity is checked: roles stay the platform's to
 * enforce, and the token goes on unchanged.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  private static final byte[] UNAUTHORIZED =
      """
      {"type":"about:blank","title":"Unauthorized","status":401,"detail":"A valid bearer token is required."}"""
          .getBytes(StandardCharsets.UTF_8);

  @Bean
  SecurityWebFilterChain edge(ServerHttpSecurity http) {
    return http.authorizeExchange(
            exchanges ->
                exchanges
                    .pathMatchers("/actuator/health/**")
                    .permitAll()
                    // A Remittance Advice's link: its token is the credential (suppliers), and
                    // the platform checks it. Same path as the remittance-link route in
                    // application.properties: change both together.
                    .pathMatchers(HttpMethod.GET, "/v1/remittance-links/*")
                    .permitAll()
                    .anyExchange()
                    .authenticated())
        .oauth2ResourceServer(
            server ->
                server.jwt(Customizer.withDefaults()).authenticationEntryPoint(problemEntryPoint()))
        // Bearer tokens, no cookies: there is no session for a cross-site request to ride on.
        .csrf(
            ServerHttpSecurity.CsrfSpec
                ::disable) // NOSONAR java:S4502, reviewed: see the line above
        .build();
  }

  // A 401 carries an RFC 9457 body like the platform's. The bearer entry point still sets the
  // status and WWW-Authenticate, but ends by completing the response empty, so that completion
  // writes the body instead.
  private static ServerAuthenticationEntryPoint problemEntryPoint() {
    var bearer = new BearerTokenServerAuthenticationEntryPoint();
    return (exchange, exception) -> {
      var response =
          new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> setComplete() {
              getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
              return writeWith(Mono.just(bufferFactory().wrap(UNAUTHORIZED)));
            }
          };
      return bearer.commence(exchange.mutate().response(response).build(), exception);
    };
  }
}
