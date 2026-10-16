package dev.rgonz.payments.simulator;

import dev.rgonz.payments.contracts.client.BuyersApi;
import dev.rgonz.payments.contracts.client.CreditApi;
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
    return signedInAs("issuer-admin", builder, clients, platformUrl).createClient(BuyersApi.class);
  }

  @Bean
  CreditApi creditApi(
      RestClient.Builder builder,
      OAuth2AuthorizedClientManager clients,
      @Value("${simulator.platform-url}") String platformUrl) {
    return signedInAs("issuer-admin", builder, clients, platformUrl).createClient(CreditApi.class);
  }

  @Bean
  InvoicesApi invoicesApi(
      RestClient.Builder builder,
      OAuth2AuthorizedClientManager clients,
      @Value("${simulator.platform-url}") String platformUrl) {
    return signedInAs("acme-erp", builder, clients, platformUrl).createClient(InvoicesApi.class);
  }

  // Two more clients of the same API, kept inside AcmeApprovers so AcmeErp's stays the only bean.
  @Bean
  AcmeApprovers acmeApprovers(
      RestClient.Builder builder,
      OAuth2AuthorizedClientManager clients,
      @Value("${simulator.platform-url}") String platformUrl) {
    return new AcmeApprovers(
        signedInAs("acme-approver-1", builder.clone(), clients, platformUrl)
            .createClient(InvoicesApi.class),
        signedInAs("acme-approver-2", builder.clone(), clients, platformUrl)
            .createClient(InvoicesApi.class));
  }

  // The registration ID names one of the spring.security.oauth2.client.registration entries.
  private static HttpServiceProxyFactory signedInAs(
      String registrationId,
      RestClient.Builder builder,
      OAuth2AuthorizedClientManager clients,
      String platformUrl) {
    var signIn = new OAuth2ClientHttpRequestInterceptor(clients);
    signIn.setClientRegistrationIdResolver(request -> registrationId);
    var restClient = builder.baseUrl(platformUrl).requestInterceptor(signIn).build();
    return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build();
  }
}
