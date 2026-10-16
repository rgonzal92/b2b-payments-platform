package dev.rgonz.payments.simulator;

import dev.rgonz.payments.contracts.client.BuyersApi;
import dev.rgonz.payments.contracts.client.CreditApi;
import dev.rgonz.payments.contracts.client.InvoicesApi;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc.IssuerAuthorizationBlockingStub;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.ChannelBuilderOptions;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.grpc.client.interceptor.security.BearerTokenAuthenticationInterceptor;
import org.springframework.grpc.client.interceptor.security.TokenSupplier;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Clients for the platform's API and its Network link, generated from the contracts, each signed in
 * as the role it plays.
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

  // The Network link: the channel named platform (spring.grpc.client.channel.platform), every call
  // carrying the network client's token as authorization: Bearer metadata.
  @Bean
  IssuerAuthorizationBlockingStub issuerAuthorization(
      GrpcChannelFactory channels, OAuth2AuthorizedClientManager clients) {
    // Typed: a bare lambda fits both the Supplier and the TokenSupplier constructor.
    TokenSupplier networkToken = () -> accessToken("network", clients);
    var signIn = new BearerTokenAuthenticationInterceptor(networkToken);
    var options = ChannelBuilderOptions.defaults().withInterceptors(List.of(signIn));
    return IssuerAuthorizationGrpc.newBlockingStub(channels.createChannel("platform", options));
  }

  // The client's token, fetched from Keycloak the first time and again once it has expired.
  private static String accessToken(String registrationId, OAuth2AuthorizedClientManager clients) {
    var request =
        OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
            .principal(registrationId)
            .build();
    var client =
        Objects.requireNonNull(clients.authorize(request), "the " + registrationId + " client");
    return client.getAccessToken().getTokenValue();
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
