package dev.rgonz.payments.simulator;

import static dev.rgonz.payments.simulator.GlobexSupplier.ADVICE_PATH;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class SimulatorSecurity {

  // The platform POSTs Remittance Advice server to server, and the demos are started with curl,
  // all without cookies: the simulator signs nobody in, so there is no session for a forged
  // cross-site request to ride on.
  private static final String[] WITHOUT_COOKIES = {
    ADVICE_PATH, AcmeDemo.DEMO_PATH, ClearingFileDemo.DEMO_PATH
  };

  // The simulator is a local test driver: it signs in to the platform as a client and serves
  // nothing to protect.
  @Bean
  SecurityFilterChain open(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
        .csrf(csrf -> csrf.ignoringRequestMatchers(WITHOUT_COOKIES)) // NOSONAR java:S4502
        .build();
  }

  // Gets client-credentials tokens outside any web request, such as at startup.
  @Bean
  OAuth2AuthorizedClientManager authorizedClientManager(
      ClientRegistrationRepository registrations, OAuth2AuthorizedClientService clients) {
    return new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
  }
}
