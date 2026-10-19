package dev.rgonz.payments.authorization;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.security.AuthenticationProcessInterceptor;
import org.springframework.grpc.server.security.GrpcSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * The Network's Keycloak token on the gRPC link, as the platform's SecurityConfig checks it, and
 * the service's HTTP (actuator), where only health is open.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  // The Network link (gRPC): every call needs the Network's token, sent as `authorization: Bearer`
  // metadata, with the realm role network. No token or a bad one is UNAUTHENTICATED; another
  // client's token is PERMISSION_DENIED.
  @Bean
  @GlobalServerInterceptor
  AuthenticationProcessInterceptor networkLink(
      GrpcSecurity grpc,
      @Value("${spring.security.oauth2.resourceserver.jwt.authorities-claim-name}")
          String authoritiesClaim,
      @Value("${spring.security.oauth2.resourceserver.jwt.authority-prefix}")
          String authorityPrefix)
      throws Exception {
    var authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName(authoritiesClaim);
    authorities.setAuthorityPrefix(authorityPrefix);
    var tokens = new JwtAuthenticationConverter();
    tokens.setJwtGrantedAuthoritiesConverter(authorities);
    return grpc.authorizeRequests(requests -> requests.allRequests().hasAuthority("ROLE_network"))
        .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(tokens)))
        .build();
  }

  // Spring gRPC's security is blocking and takes a JwtDecoder; Spring Boot makes only the reactive
  // one in a WebFlux app. Built at the first call, not at startup, so the service starts while
  // Keycloak is down, as Spring Boot's own decoder does. With jwk-set-uri set, as in the cluster,
  // the keys come from it and iss is still checked against issuer-uri, which isn't fetched: as
  // Spring Boot's own decoder does with both set. Unlike Boot's, it ignores jwt.audiences,
  // jwt.jws-algorithms (RS256 only, Keycloak's) and any OAuth2TokenValidator beans.
  @Bean
  JwtDecoder jwtDecoder(
      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
      @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri) {
    if (jwkSetUri.isEmpty()) {
      return new SupplierJwtDecoder(() -> JwtDecoders.fromIssuerLocation(issuer));
    }
    var decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
    return decoder;
  }

  // Adding Spring Security would otherwise close every HTTP path, health included.
  @Bean
  SecurityWebFilterChain http(ServerHttpSecurity http) {
    return http.authorizeExchange(
            exchanges ->
                exchanges
                    .pathMatchers("/actuator/health/**")
                    .permitAll()
                    .anyExchange()
                    .authenticated())
        .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
        // Bearer tokens, no cookies: there is no session for a cross-site request to ride on.
        .csrf(
            ServerHttpSecurity.CsrfSpec
                ::disable) // NOSONAR java:S4502, reviewed: see the line above
        .build();
  }
}
