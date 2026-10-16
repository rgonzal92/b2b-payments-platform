package dev.rgonz.payments.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.contracts.network.AuthorizationResponse;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc.IssuerAuthorizationBlockingStub;
import dev.rgonz.payments.contracts.network.ReversalRequest;
import dev.rgonz.payments.contracts.network.ReversalResponse;
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
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
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
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
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
 * The Network's Reversals cancel them ({@link Reversals}).
 */
@Import({TestcontainersConfiguration.class, AuthorizeTests.NetworkTokens.class})
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizeTests {

  private static final Currency USD = Currency.getInstance("USD");
  private static final long AMOUNT = 1_240_000;
  private static final String ACCEPTOR_ID = "GLOBEX000000001";
  // DE 14, as printed on the card.
  private static final DateTimeFormatter EXPIRATION_DATE =
      DateTimeFormatter.ofPattern("yyMM").withZone(ZoneOffset.UTC);

  /**
   * Every bearer token decodes as the Network's, so the test can call over gRPC. Imported rather
   * than a nested {@code @TestConfiguration}: Spring Framework 7.0 ignores an enclosing class's
   * nested configuration in a {@code @Nested} class, which would then start a context of its own.
   */
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
  @Autowired DataSource dataSource;
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
    var request = chargeOn(card).setCardAcceptorId(chargesAcceptorId);

    var response = authorize(request);

    assertThat(response.getResponseCode()).isEqualTo("57");
    assertThat(response.getAuthorizationCode()).isEmpty();
    assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.ACTIVE);
    assertThat(availableCredit(buyerId)).isEqualTo(creditBefore);
    assertThat(recorded(request.getRetrievalReferenceNumber()))
        .containsEntry("card_token", card.cardToken())
        .containsEntry("acceptor_id", chargesAcceptorId)
        .containsEntry("response_code", "57")
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

    assertThat(grpcStatusOf(() -> authorize(chargeOn(card).clearRetrievalReferenceNumber())))
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

  /**
   * The Network cancels an Authorization by its request ID. Every Reversal is acknowledged with
   * {@code 00}; only an approved Authorization changes, and the Hold stays on every path.
   */
  @Nested
  class Reversals {

    @Test
    void anApprovedAuthorizationIsReversedAndItsCardCanBeChargedAgainWhileItsHoldStays()
        throws Exception {
      var buyerId = buyerWithCreditLine(10_000_000);
      var card = issuedCard(buyerId);
      var charge = chargeOn(card);
      var requestId = charge.getRetrievalReferenceNumber();
      var approval = authorize(charge);
      assertThat(approval.getResponseCode()).isEqualTo("00");
      var creditBefore = availableCredit(buyerId);

      var response = reverse(requestId);

      assertThat(response).isEqualTo(acknowledgement(requestId));
      // The record keeps the answer the Network was given.
      assertThat(recorded(requestId))
          .containsEntry("response_code", "00")
          .containsEntry("authorization_code", approval.getAuthorizationCode())
          .containsEntry("status", "REVERSED");
      assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.ACTIVE);
      assertThat(availableCredit(buyerId)).isEqualTo(creditBefore);
      assertThat(authorize(chargeOn(card)).getResponseCode()).isEqualTo("00");
      assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.AUTHORIZED);
    }

    // Sent twice, as a Network that missed the first acknowledgement would.
    @Test
    void aDeclinedAuthorizationIsAcknowledgedAndNothingChanges() throws Exception {
      var buyerId = buyerWithCreditLine(10_000_000);
      var card = issuedCard(buyerId);
      var charge = chargeOn(card).setAmountMinor(AMOUNT + 1);
      var requestId = charge.getRetrievalReferenceNumber();
      assertThat(authorize(charge).getResponseCode()).isEqualTo("61");
      var creditBefore = availableCredit(buyerId);

      var response = reverse(requestId);
      var again = reverse(requestId);

      assertThat(response).isEqualTo(acknowledgement(requestId));
      assertThat(again).isEqualTo(acknowledgement(requestId));
      assertThat(recorded(requestId))
          .containsEntry("response_code", "61")
          .containsEntry("status", "DECLINED");
      assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.ACTIVE);
      assertThat(availableCredit(buyerId)).isEqualTo(creditBefore);
    }

    // Only the reversed Authorization's own record decides what its Reversal does: reversing A
    // again mustn't free the card that B now holds.
    @Test
    void aRepeatedReversalIsAcknowledgedAndChangesNothingMore() throws Exception {
      var buyerId = buyerWithCreditLine(10_000_000);
      var card = issuedCard(buyerId);
      var first = chargeOn(card);
      var firstRequestId = first.getRetrievalReferenceNumber();
      assertThat(authorize(first).getResponseCode()).isEqualTo("00");
      assertThat(reverse(firstRequestId)).isEqualTo(acknowledgement(firstRequestId));
      var second = chargeOn(card);
      assertThat(authorize(second).getResponseCode()).isEqualTo("00");
      var creditBefore = availableCredit(buyerId);

      var response = reverse(firstRequestId);

      assertThat(response).isEqualTo(acknowledgement(firstRequestId));
      assertThat(recorded(firstRequestId)).containsEntry("status", "REVERSED");
      assertThat(recorded(second.getRetrievalReferenceNumber()))
          .containsEntry("status", "APPROVED");
      assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.AUTHORIZED);
      assertThat(availableCredit(buyerId)).isEqualTo(creditBefore);
    }

    // The Network gave up on a slow Authorization and reversed it, twice, before the platform
    // recorded it.
    @Test
    void aReversalBeforeItsAuthorizationMakesTheLateAuthorizationAnswerDoNotHonour() {
      var card = issuedCard(buyerWithCreditLine(10_000_000));
      var charge = chargeOn(card);
      var requestId = charge.getRetrievalReferenceNumber();

      var reversal = reverse(requestId);
      var again = reverse(requestId);
      var late = authorize(charge);

      assertThat(reversal).isEqualTo(acknowledgement(requestId));
      assertThat(again).isEqualTo(acknowledgement(requestId));
      assertThat(late.getResponseCode()).isEqualTo("05");
      assertThat(late.getAuthorizationCode()).isEmpty();
      assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.ACTIVE);
      assertThat(recordedCount(requestId)).isOne();
      assertThat(recorded(requestId))
          .containsEntry("card_token", null)
          .containsEntry("response_code", "05")
          .containsEntry("authorization_code", null)
          .containsEntry("status", "REVERSED");
    }

    // The Reversal commits while the Authorization is running: it has started, but a lock on the
    // cards holds it before it reads the card, so it claims its request ID only afterwards.
    @Test
    void anAuthorizationStillRunningWhenItsReversalCommitsAnswersDoNotHonour() throws Exception {
      var card = issuedCard(buyerWithCreditLine(10_000_000));
      var charge = chargeOn(card).build();
      var requestId = charge.getRetrievalReferenceNumber();

      // Closed in reverse order: the lock's connection rolls back before the executor waits.
      try (var network = Executors.newSingleThreadExecutor();
          var cardsLock = dataSource.getConnection()) {
        cardsLock.setAutoCommit(false);
        try (var lock = cardsLock.createStatement()) {
          lock.execute("lock table cards.card in access exclusive mode");
        }
        var late = network.submit(() -> authorize(charge));
        await().atMost(Duration.ofSeconds(5)).until(() -> lockWaiters() >= 1);

        var reversal = reverse(requestId);
        cardsLock.rollback();

        assertThat(reversal).isEqualTo(acknowledgement(requestId));
        var answer = late.get(10, TimeUnit.SECONDS);
        assertThat(answer.getResponseCode()).isEqualTo("05");
        assertThat(answer.getAuthorizationCode()).isEmpty();
      }
      assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.ACTIVE);
      assertThat(recorded(requestId))
          .containsEntry("card_token", null)
          .containsEntry("status", "REVERSED");
    }

    // The Authorization claims its request ID first, and approves after the Reversal arrived: a
    // lock on the card holds it after it recorded its approval, before it moved the card. The
    // Reversal's claim waits for it to commit, then reverses it.
    @Test
    void anAuthorizationApprovedAfterItsReversalArrivedIsReversed() throws Exception {
      var card = issuedCard(buyerWithCreditLine(10_000_000));
      var charge = chargeOn(card).build();
      var requestId = charge.getRetrievalReferenceNumber();

      // Closed in reverse order: the lock's connection rolls back before the executor waits.
      try (var network = Executors.newFixedThreadPool(2);
          var cardLock = dataSource.getConnection()) {
        cardLock.setAutoCommit(false);
        try (var lock =
            cardLock.prepareStatement("select 1 from cards.card where card_token = ? for update")) {
          lock.setString(1, card.cardToken());
          lock.executeQuery().close();
        }
        var slow = network.submit(() -> authorize(charge));
        await().atMost(Duration.ofSeconds(5)).until(() -> lockWaiters() >= 1);
        var reversal = network.submit(() -> reverse(requestId));
        await().atMost(Duration.ofSeconds(5)).until(() -> lockWaiters() >= 2);

        cardLock.rollback();

        assertThat(slow.get(10, TimeUnit.SECONDS).getResponseCode()).isEqualTo("00");
        assertThat(reversal.get(10, TimeUnit.SECONDS)).isEqualTo(acknowledgement(requestId));
      }
      assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.ACTIVE);
      assertThat(recorded(requestId))
          .containsEntry("response_code", "00")
          .containsEntry("status", "REVERSED");
    }

    @ParameterizedTest(name = "retrieval reference number \"{0}\"")
    @ValueSource(strings = {"", "   "})
    void aReversalWithoutARetrievalReferenceNumberIsRefusedAndNothingIsRecorded(String requestId) {
      assertThat(grpcStatusOf(() -> reverse(requestId))).isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(recordedCount(requestId)).isZero();
    }

    // Connections to the test database waiting for a lock: the requests a test holds up.
    private long lockWaiters() {
      return jdbc.sql(
              """
              select count(*) from pg_stat_activity
               where datname = current_database() and wait_event_type = 'Lock'
              """)
          .query(Long.class)
          .single();
    }

    private ReversalResponse reverse(String requestId) {
      return network()
          .reverse(ReversalRequest.newBuilder().setRetrievalReferenceNumber(requestId).build());
    }

    // Every Reversal's answer: its request ID and 00, whatever it changed.
    private static ReversalResponse acknowledgement(String requestId) {
      return ReversalResponse.newBuilder()
          .setRetrievalReferenceNumber(requestId)
          .setResponseCode("00")
          .build();
    }
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
    return network().authorize(request);
  }

  // The gRPC status of a call refused before any answer.
  private static Status.Code grpcStatusOf(ThrowingCallable call) {
    return assertThatExceptionOfType(StatusRuntimeException.class)
        .isThrownBy(call)
        .actual()
        .getStatus()
        .getCode();
  }

  // Signed in as the Network (NetworkTokens), with a deadline so a hang fails the test quickly.
  private IssuerAuthorizationBlockingStub network() {
    return IssuerAuthorizationGrpc.newBlockingStub(channel)
        .withDeadlineAfter(5, TimeUnit.SECONDS)
        .withInterceptors(new BearerTokenAuthenticationInterceptor("network-token"));
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
