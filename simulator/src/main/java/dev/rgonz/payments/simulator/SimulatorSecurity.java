package dev.rgonz.payments.simulator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class SimulatorSecurity {

  // The simulator is a local test driver: it signs in to the platform as a client and serves
  // nothing to protect.
  @Bean
  SecurityFilterChain open(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
        .csrf(AbstractHttpConfigurer::disable)
        .build();
  }

  // Gets client-credentials tokens outside any web request, such as at startup.
  @Bean
  OAuth2AuthorizedClientManager authorizedClientManager(
      ClientRegistrationRepository registrations, OAuth2AuthorizedClientService clients) {
    return new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
  }
}
