package dev.rgonz.payments.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
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
import dev.rgonz.payments.platform.vault.Vault;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * The Network asks for an Authorization over gRPC, with a mocked Network token, on PostgreSQL: a
 * Charge that passes every rule is approved; each decline leaves the card and its Hold as they
 * were; every answer is recorded, once per request ID, and a resent request gets the first answer.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizeTests {

  private static final Currency USD = Currency.getInstance("USD");
  private static final long AMOUNT = 1_240_000;
  private static final String ACCEPTOR_ID = "GLOBEX-ACCEPTOR";
  // DE 14, as printed on the card.
  private static final DateTimeFormatter EXPIRATION_DATE =
      DateTimeFormatter.ofPattern("yyMM").withZone(ZoneOffset.UTC);

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
    var request = chargeOn(card);
    var requestId = request.getRetrievalReferenceNumber();

    var response = authorize(request);

    assertThat(response.getRetrievalReferenceNumber()).isEqualTo(requestId);
    assertThat(response.getResponseCode()).isEqualTo("00");
    assertThat(response.getAuthorizationCode()).matches("[A-Z0-9]{6}");
    assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.AUTHORIZED);
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
    var response =
        authorize(
            AuthorizationRequest.newBuilder()
                .setPrimaryAccountNumber("4000000000000002")
                .setSecurityCode("123")
                .setExpirationDate("2611")
                .setAmountMinor(AMOUNT)
                .setCurrencyCode("USD")
                .setRetrievalReferenceNumber(requestId)
                .setCardAcceptorId(ACCEPTOR_ID));

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

  @Test
  void aCancelledCardIsDeclinedAndStaysCancelled() throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var card = issuedCard(buyerId);
    cards.cancel(card.cardToken());
    var creditBefore = availableCredit(buyerId);
    var request = chargeOn(card);
    var requestId = request.getRetrievalReferenceNumber();

    var response = authorize(request);

    assertThat(response.getResponseCode()).isEqualTo("54");
    assertThat(response.getAuthorizationCode()).isEmpty();
    assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.CANCELLED);
    assertThat(availableCredit(buyerId)).isEqualTo(creditBefore);
    assertThat(recorded(requestId))
        .containsEntry("card_token", card.cardToken())
        .containsEntry("response_code", "54")
        .containsEntry("authorization_code", null)
        .containsEntry("status", "DECLINED");
  }

  @Test
  void aWrongSecurityCodeIsDeclinedAndLeavesTheCardAndItsHoldAsTheyWere() throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var card = issuedCard(buyerId);
    var creditBefore = availableCredit(buyerId);
    var securityCode = vault.cardDetails(card.cardToken()).securityCode();
    var request =
        chargeOn(card)
            .setSecurityCode(String.format("%03d", (Integer.parseInt(securityCode) + 1) % 1_000));

    var response = authorize(request);

    assertThat(response.getResponseCode()).isEqualTo("N7");
    assertThat(response.getAuthorizationCode()).isEmpty();
    assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.ACTIVE);
    assertThat(availableCredit(buyerId)).isEqualTo(creditBefore);
    assertThat(recorded(request.getRetrievalReferenceNumber()))
        .containsEntry("card_token", card.cardToken())
        .containsEntry("response_code", "N7")
        .containsEntry("authorization_code", null)
        .containsEntry("status", "DECLINED");
  }

  @ParameterizedTest(name = "{0}: {4}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          # Charge                                | expiry | amount  | currency | answer
          the wrong expiry month                  | 2001   | 1240000 | USD      | 54
          one minor unit above the card's amount  |        | 1240001 | USD      | 61
          another currency                        |        | 1240000 | EUR      | 61
          a currency that isn't ISO 4217          |        | 1240000 | DOLLARS  | 61
          a zero amount                           |        | 0       | USD      | 13
          """)
  void aDeclineIsRecordedAndLeavesTheCardAndItsHoldAsTheyWere(
      String charge, String expirationDate, long amountMinor, String currencyCode, String answer)
      throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var card = issuedCard(buyerId);
    var creditBefore = availableCredit(buyerId);
    var request = chargeOn(card).setAmountMinor(amountMinor).setCurrencyCode(currencyCode);
    if (expirationDate != null) {
      request.setExpirationDate(expirationDate);
    }

    var response = authorize(request);

    assertThat(response.getResponseCode()).isEqualTo(answer);
    assertThat(response.getAuthorizationCode()).isEmpty();
    assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.ACTIVE);
    assertThat(availableCredit(buyerId)).isEqualTo(creditBefore);
    assertThat(recorded(request.getRetrievalReferenceNumber()))
        .containsEntry("card_token", card.cardToken())
        .containsEntry("amount_minor", amountMinor)
        .containsEntry("currency", currencyCode)
        .containsEntry("response_code", answer)
        .containsEntry("authorization_code", null)
        .containsEntry("status", "DECLINED");
  }

  @Test
  void aSecondChargeOnAnApprovedCardIsDeclinedAndTheCardStaysAuthorized() throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var card = issuedCard(buyerId);
    assertThat(authorize(chargeOn(card)).getResponseCode()).isEqualTo("00");
    var creditBefore = availableCredit(buyerId);
    var second = chargeOn(card);

    var response = authorize(second);

    assertThat(response.getResponseCode()).isEqualTo("57");
    assertThat(response.getAuthorizationCode()).isEmpty();
    assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.AUTHORIZED);
    assertThat(availableCredit(buyerId)).isEqualTo(creditBefore);
    assertThat(recorded(second.getRetrievalReferenceNumber()))
        .containsEntry("card_token", card.cardToken())
        .containsEntry("response_code", "57")
        .containsEntry("authorization_code", null)
        .containsEntry("status", "DECLINED");
  }

  @Test
  void aRequestWithoutARetrievalReferenceNumberIsRefusedAndNothingIsRecorded() {
    var card = issuedCard(buyerWithCreditLine(10_000_000));

    assertThat(
            assertThatExceptionOfType(StatusRuntimeException.class)
                .isThrownBy(() -> authorize(chargeOn(card).clearRetrievalReferenceNumber()))
                .actual()
                .getStatus()
                .getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.ACTIVE);
    assertThat(
            jdbc.sql(
                    """
                    select count(*) from "authorization".authorizations
                     where request_id = '' or card_token = :cardToken
                    """)
                .param("cardToken", card.cardToken())
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void aResentRequestGetsTheFirstAnswerWhateverTheRulesWouldSayNow() {
    var card = issuedCard(buyerWithCreditLine(10_000_000));
    var request = chargeOn(card);
    var first = authorize(request);

    // The card is AUTHORIZED now and the amount above the card's: both would decline a new Charge.
    var resent = authorize(request.setAmountMinor(AMOUNT + 1));

    assertThat(first.getResponseCode()).isEqualTo("00");
    assertThat(resent).isEqualTo(first);
    assertThat(recordedCount(request.getRetrievalReferenceNumber())).isOne();
    assertThat(recorded(request.getRetrievalReferenceNumber()))
        .containsEntry("amount_minor", AMOUNT)
        .containsEntry("authorization_code", first.getAuthorizationCode());
  }

  @Test
  void requestsWithOneRequestIdAtOnceRecordOneAuthorizationAndAllGetItsAnswer() throws Exception {
    var card = issuedCard(buyerWithCreditLine(10_000_000));
    // Built once: a protobuf builder isn't safe to share between threads.
    var request = chargeOn(card).build();
    var requests = 8;
    var start = new CountDownLatch(1);
    var responses = new ArrayList<Future<AuthorizationResponse>>();

    try (var network = Executors.newFixedThreadPool(requests)) {
      for (var i = 0; i < requests; i++) {
        responses.add(
            network.submit(
                () -> {
                  start.await();
                  return authorize(request);
                }));
      }
      start.countDown();
      var first = responses.getFirst().get(10, TimeUnit.SECONDS);
      for (var response : responses) {
        assertThat(response.get(10, TimeUnit.SECONDS)).isEqualTo(first);
      }
      assertThat(first.getResponseCode()).isEqualTo("00");
    }
    assertThat(recordedCount(request.getRetrievalReferenceNumber())).isOne();
    assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.AUTHORIZED);
  }

  @Test
  void ofChargesWithDifferentRequestIdsAtOnceOnOneCardExactlyOneIsApproved() throws Exception {
    var card = issuedCard(buyerWithCreditLine(10_000_000));
    var charges = 8;
    var start = new CountDownLatch(1);
    var responses = new ArrayList<Future<AuthorizationResponse>>();

    try (var network = Executors.newFixedThreadPool(charges)) {
      for (var i = 0; i < charges; i++) {
        var request = chargeOn(card).build();
        responses.add(
            network.submit(
                () -> {
                  start.await();
                  return authorize(request);
                }));
      }
      start.countDown();
      var answers = new ArrayList<String>();
      for (var response : responses) {
        var answer = response.get(10, TimeUnit.SECONDS);
        answers.add(answer.getResponseCode());
        assertThat(recorded(answer.getRetrievalReferenceNumber()))
            .containsEntry("response_code", answer.getResponseCode());
      }
      assertThat(answers).containsOnlyOnce("00").containsOnly("00", "57");
    }
    assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.AUTHORIZED);
  }

  // Everything the Network would send for a Charge of the card's whole amount, under a new request
  // ID: the PAN, security code and expiry as the Supplier read them through its link.
  private AuthorizationRequest.Builder chargeOn(SingleUseCard card) {
    var details = vault.cardDetails(card.cardToken());
    return AuthorizationRequest.newBuilder()
        .setPrimaryAccountNumber(details.pan())
        .setSecurityCode(details.securityCode())
        .setExpirationDate(EXPIRATION_DATE.format(card.expiresAt()))
        .setAmountMinor(AMOUNT)
        .setCurrencyCode("USD")
        .setRetrievalReferenceNumber(UUID.randomUUID().toString())
        .setCardAcceptorId(ACCEPTOR_ID)
        .setCardAcceptorName("Globex, Springfield");
  }

  private AuthorizationResponse authorize(AuthorizationRequest.Builder request) {
    return authorize(request.build());
  }

  private AuthorizationResponse authorize(AuthorizationRequest request) {
    return IssuerAuthorizationGrpc.newBlockingStub(channel)
        .withDeadlineAfter(5, TimeUnit.SECONDS)
        .withInterceptors(new BearerTokenAuthenticationInterceptor("network-token"))
        .authorize(request);
  }

  // Issued as the Payment saga issues it: the Hold first, then the card, for the same Payment.
  private SingleUseCard issuedCard(BuyerId buyerId) {
    var paymentId = UUID.randomUUID();
    var amount = new Money(AMOUNT, USD);
    assertThat(holds.place(paymentId, buyerId, amount)).isEqualTo(Holds.Outcome.PLACED);
    return cards.issueSingleUse(paymentId, buyerId, UUID.randomUUID(), amount, Instant.now());
  }

  private SingleUseCard.Status statusOf(SingleUseCard card) {
    return cards.find(card.cardToken()).orElseThrow().status();
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

  private long recordedCount(String requestId) {
    return jdbc.sql(
            """
            select count(*) from "authorization".authorizations where request_id = :requestId
            """)
        .param("requestId", requestId)
        .query(Long.class)
        .single();
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
