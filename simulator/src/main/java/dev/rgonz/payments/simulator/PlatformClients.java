package dev.rgonz.payments.simulator;

import dev.rgonz.payments.contracts.client.BuyersApi;
import dev.rgonz.payments.contracts.client.InvoicesApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Clients for the platform's API, generated from the contract, each signed in as the role it plays.
 */
@Configuration(proxyBeanMethods = false)
class PlatformClients {

  @Bean
  BuyersApi buyersApi(
      RestClient.Builder builder,
      OAuth2AuthorizedClientManager clients,
      @Value("${simulator.platform-url}") String platformUrl) {
    var issuerAdmin = new OAuth2ClientHttpRequestInterceptor(clients);
    issuerAdmin.setClientRegistrationIdResolver(request -> "issuer-admin");
    var restClient = builder.baseUrl(platformUrl).requestInterceptor(issuerAdmin).build();
    return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
        .build()
        .createClient(BuyersApi.class);
  }

  @Bean
  InvoicesApi invoicesApi(
      RestClient.Builder builder,
      OAuth2AuthorizedClientManager clients,
      @Value("${simulator.platform-url}") String platformUrl) {
    var acmeErp = new OAuth2ClientHttpRequestInterceptor(clients);
    acmeErp.setClientRegistrationIdResolver(request -> "acme-erp");
    var restClient = builder.baseUrl(platformUrl).requestInterceptor(acmeErp).build();
    return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
        .build()
        .createClient(InvoicesApi.class);
  }
}
