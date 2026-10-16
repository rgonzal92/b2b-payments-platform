package dev.rgonz.payments.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.contracts.network.AuthorizationResponse;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc;
import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.cards.Cards;
import dev.rgonz.payments.platform.cards.SingleUseCard;
import dev.rgonz.payments.platform.ledger.Holds;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import dev.rgonz.payments.platform.suppliers.SupplierOnInvoice;
import dev.rgonz.payments.platform.suppliers.Suppliers;
import dev.rgonz.payments.platform.vault.Vault;
import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.interceptor.security.BearerTokenAuthenticationInterceptor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The Network asks for an Authorization over gRPC, with a mocked Network token, on PostgreSQL: an
 * issued card is approved, anything else declined, and every answer recorded.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizeTests {

  private static final Currency USD = Currency.getInstance("USD");
  private static final long AMOUNT = 1_240_000;
  private static final String ACCEPTOR_ID = "GLOBEX000000001";

  /** Every bearer token decodes as the Network's, so the test can call over gRPC. */
  @TestConfiguration(proxyBeanMethods = false)
  static class NetworkTokens {

    @Bean
    JwtDecoder networkTokens() {
      return token ->
          Jwt.withTokenValue(token)
              .header("alg", "none")
              .subject("service-account-network")
              .claim("roles", List.of("network"))
              .build();
    }
  }

  @Autowired MockMvcTester mvc;
  @Autowired JdbcClient jdbc;
  @Autowired Cards cards;
  @Autowired Holds holds;
  @Autowired Vault vault;
  @Autowired Suppliers suppliers;

  @LocalGrpcServerPort int port;

  private ManagedChannel channel;

  @BeforeEach
  void connect() {
    channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
  }

  @AfterEach
  void disconnect() {
    channel.shutdownNow();
  }

  @Test
  void anIssuedActiveCardIsApprovedAndAuthorizedAndItsHoldIsUntouched() throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var card = issuedCard(buyerId);
    var creditBefore = availableCredit(buyerId);
    var requestId = UUID.randomUUID().toString();

    var response = authorize(requestId, vault.cardDetails(card.cardToken()).pan());

    assertThat(response.getRetrievalReferenceNumber()).isEqualTo(requestId);
    assertThat(response.getResponseCode()).isEqualTo("00");
    assertThat(response.getAuthorizationCode()).matches("[A-Z0-9]{6}");
    assertThat(cards.find(card.cardToken()))
        .get()
        .extracting(SingleUseCard::status)
        .isEqualTo(SingleUseCard.Status.AUTHORIZED);
    assertThat(availableCredit(buyerId)).isEqualTo(creditBefore);
    assertThat(recorded(requestId))
        .containsEntry("card_token", card.cardToken())
        .containsEntry("amount_minor", AMOUNT)
        .containsEntry("currency", "USD")
        .containsEntry("acceptor_id", ACCEPTOR_ID)
        .containsEntry("response_code", "00")
        .containsEntry("authorization_code", response.getAuthorizationCode())
        .containsEntry("status", "APPROVED");
  }

  @Test
  void anUnknownPanIsDeclinedAsAnInvalidCard() {
    var requestId = UUID.randomUUID().toString();

    // Outside the platform's 990000 range, so no card has it.
    var response = authorize(requestId, "4000000000000002");

    assertThat(response.getRetrievalReferenceNumber()).isEqualTo(requestId);
    assertThat(response.getResponseCode()).isEqualTo("14");
    assertThat(response.getAuthorizationCode()).isEmpty();
    assertThat(recorded(requestId))
        .containsEntry("card_token", null)
        .containsEntry("amount_minor", AMOUNT)
        .containsEntry("currency", "USD")
        .containsEntry("acceptor_id", ACCEPTOR_ID)
        .containsEntry("response_code", "14")
        .containsEntry("authorization_code", null)
        .containsEntry("status", "DECLINED");
  }

  // A card whose Supplier has no Acceptor ID can't be charged by anyone.
  @ParameterizedTest(name = "the Supplier has {0}, the Charge carries {1}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          # The Supplier's Acceptor ID (none when empty) | the Charge's
          GLOBEX000000001 | OTHER0000000002
                          | GLOBEX000000001
          """)
  void aChargeNotFromTheCardsSuppliersAcceptorIdIsDeclinedAndChangesNeitherTheCardNorItsHold(
      String suppliersAcceptorId, String chargesAcceptorId) throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var card = issuedCard(buyerId, suppliersAcceptorId);
    var creditBefore = availableCredit(buyerId);
    var requestId = UUID.randomUUID().toString();

    var response =
        authorize(requestId, vault.cardDetails(card.cardToken()).pan(), chargesAcceptorId);

    assertThat(response.getResponseCode()).isEqualTo("57");
    assertThat(response.getAuthorizationCode()).isEmpty();
    assertThat(cards.find(card.cardToken()))
        .get()
        .extracting(SingleUseCard::status)
        .isEqualTo(SingleUseCard.Status.ACTIVE);
    assertThat(availableCredit(buyerId)).isEqualTo(creditBefore);
    assertThat(recorded(requestId))
        .containsEntry("card_token", card.cardToken())
        .containsEntry("acceptor_id", chargesAcceptorId)
        .containsEntry("response_code", "57")
        .containsEntry("authorization_code", null)
        .containsEntry("status", "DECLINED");
  }

  @Test
  void aCancelledCardIsDeclinedAndStaysCancelled() {
    var card = issuedCard(buyerWithCreditLine(10_000_000));
    cards.cancel(card.cardToken());
    var requestId = UUID.randomUUID().toString();

    var response = authorize(requestId, vault.cardDetails(card.cardToken()).pan());

    assertThat(response.getResponseCode()).isEqualTo("54");
    assertThat(response.getAuthorizationCode()).isEmpty();
    assertThat(cards.find(card.cardToken()))
        .get()
        .extracting(SingleUseCard::status)
        .isEqualTo(SingleUseCard.Status.CANCELLED);
    assertThat(recorded(requestId))
        .containsEntry("card_token", card.cardToken())
        .containsEntry("response_code", "54")
        .containsEntry("authorization_code", null)
        .containsEntry("status", "DECLINED");
  }

  private AuthorizationResponse authorize(String requestId, String pan) {
    return authorize(requestId, pan, ACCEPTOR_ID);
  }

  private AuthorizationResponse authorize(String requestId, String pan, String acceptorId) {
    return IssuerAuthorizationGrpc.newBlockingStub(channel)
        .withDeadlineAfter(5, TimeUnit.SECONDS)
        .withInterceptors(new BearerTokenAuthenticationInterceptor("network-token"))
        .authorize(
            AuthorizationRequest.newBuilder()
                .setPrimaryAccountNumber(pan)
                .setAmountMinor(AMOUNT)
                .setCurrencyCode("USD")
                .setRetrievalReferenceNumber(requestId)
                .setCardAcceptorId(acceptorId)
                .setCardAcceptorName("Globex, Springfield")
                .build());
  }

  private SingleUseCard issuedCard(BuyerId buyerId) {
    return issuedCard(buyerId, ACCEPTOR_ID);
  }

  // Issued as the Payment saga issues it: the Hold first, then the card, for the same Payment, to a
  // Supplier an Invoice named with this Acceptor ID (none when null).
  private SingleUseCard issuedCard(BuyerId buyerId, String acceptorId) {
    var supplier =
        suppliers
            .relate(
                buyerId,
                new SupplierOnInvoice(
                    "TAX-" + UUID.randomUUID(),
                    "Globex",
                    "https://globex.example/remittance-advice",
                    "GLOBEX-01",
                    acceptorId))
            .supplier();
    var paymentId = UUID.randomUUID();
    var amount = new Money(AMOUNT, USD);
    assertThat(holds.place(paymentId, buyerId, amount)).isEqualTo(Holds.Outcome.PLACED);
    return cards.issueSingleUse(paymentId, buyerId, supplier.id(), amount, Instant.now());
  }

  private Map<String, Object> recorded(String requestId) {
    return jdbc.sql(
            """
            select card_token, amount_minor, currency, acceptor_id, response_code,
                   authorization_code, status
              from "authorization".authorizations
             where request_id = :requestId
            """)
        .param("requestId", requestId)
        .query()
        .singleRow();
  }

  private BuyerId buyerWithCreditLine(long creditLine) {
    var buyerId = UUID.randomUUID();
    var buyer =
        mvc.put()
            .uri("/v1/buyers/{buyerId}", buyerId)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_issuer-admin")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name": "Acme", "approvalThreshold": {"minorUnits": 1000000, "currency": "USD"}}
                """)
            .exchange();
    assertThat(buyer).hasStatus(201);
    var credit =
        mvc.put()
            .uri("/v1/buyers/{buyerId}/credit-line", buyerId)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_issuer-admin")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"minorUnits": %d, "currency": "USD"}
                """
                    .formatted(creditLine))
            .exchange();
    assertThat(credit).hasStatus(201);
    return new BuyerId(buyerId);
  }

  private long availableCredit(BuyerId buyerId) throws UnsupportedEncodingException {
    var response =
        mvc.get()
            .uri("/v1/buyers/{buyerId}/credit", buyerId.value())
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_issuer-admin")))
            .exchange();
    assertThat(response).hasStatus(200);
    return JsonPath.<Number>read(
            response.getResponse().getContentAsString(), "$.availableCredit.minorUnits")
        .longValue();
  }
}
