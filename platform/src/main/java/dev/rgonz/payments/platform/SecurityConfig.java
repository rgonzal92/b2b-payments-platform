package dev.rgonz.payments.platform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

  @Bean
  SecurityFilterChain api(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers("/actuator/health/**")
                    .permitAll()
                    // A Remittance Advice's link: its token is the credential (suppliers).
                    .requestMatchers(HttpMethod.GET, "/v1/remittance-links/*")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            server ->
                server.jwt(Customizer.withDefaults()).authenticationEntryPoint(problemEntryPoint()))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // Bearer tokens, no cookies: there is no session for a cross-site request to ride on.
        .csrf(AbstractHttpConfigurer::disable) // NOSONAR java:S4502, reviewed: see the line above
        .build();
  }

  // A 401 carries an RFC 9457 body like every other error; the bearer entry point still sets
  // WWW-Authenticate.
  private static AuthenticationEntryPoint problemEntryPoint() {
    var bearer = new BearerTokenAuthenticationEntryPoint();
    return (request, response, exception) -> {
      bearer.commence(request, response, exception);
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      response
          .getWriter()
          .write(
              """
              {"type":"about:blank","title":"Unauthorized","status":401,"detail":"A valid bearer token is required."}""");
    };
  }
}
