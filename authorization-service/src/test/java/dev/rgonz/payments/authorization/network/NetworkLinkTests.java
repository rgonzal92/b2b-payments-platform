package dev.rgonz.payments.authorization.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import dev.rgonz.payments.authorization.AuthorizationsTopic;
import dev.rgonz.payments.authorization.TestcontainersConfiguration;
import dev.rgonz.payments.authorization.cards.CardCopies;
import dev.rgonz.payments.authorization.cards.CardHashing;
import dev.rgonz.payments.authorization.cards.CardStatus;
import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.contracts.network.AuthorizationResponse;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc.IssuerAuthorizationBlockingStub;
import dev.rgonz.payments.contracts.network.ReversalRequest;
import dev.rgonz.payments.contracts.network.ReversalResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.grpc.client.interceptor.security.BearerTokenAuthenticationInterceptor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The Network asks the service for an Authorization, or cancels one with a Reversal, over gRPC with
 * a stand-in Network token, on the service's own PostgreSQL: the test plays the platform, issuing
 * each card on a real Kafka's {@code cards} topic, and the Network, calling the service's gRPC
 * server. The answers are the ones the platform gave until the cutover (#92), from the service's
 * copy of the card.
 */
@Import({TestcontainersConfiguration.class, NetworkLinkTests.NetworkTokens.class})
@SpringBootTest
class NetworkLinkTests {

  private static final long AMOUNT = 1_240_000;
  private static final String ACCEPTOR_ID = "GLOBEX000000001";
  private static final String SECURITY_CODE = "123";
  // DE 14, as printed on the card.
  private static final DateTimeFormatter EXPIRATION_DATE =
      DateTimeFormatter.ofPattern("yyMM").withZone(ZoneOffset.UTC);
  // For tests that hold the database on purpose: long enough that a held Charge isn't answered 91.
  private static final String LONG_TIME_LIMIT =
      "resilience4j.timelimiter.instances."
          + IssuerAuthorizationService.DATABASE
          + ".timeout-duration=30s";
  // Low-entropy fake PANs, one per card: 990000 followed by a counter.
  private static final AtomicLong PANS = new AtomicLong();

  /**
   * Stands in for Keycloak's decoder: {@code network-token} decodes as the Network's, {@code
   * buyer-token} as a token without the role, anything else is refused. Primary over the
   * application's own decoder, which would ask Keycloak. Imported rather than nested: Spring
   * Framework 7.0 ignores an enclosing class's nested configuration in a {@code @Nested} class.
   */
  static class NetworkTokens {

    @Bean
    @Primary
    JwtDecoder networkTokens() {
      return token -> {
        // The token check runs on the call's gRPC thread, like the handler.
        TOKEN_CHECK_THREADS.add(Thread.currentThread().getName());
        return switch (token) {
          case "network-token" -> jwt(token, "network");
          case "buyer-token" -> jwt(token, "buyer");
          default -> throw new BadJwtException("Not a test token.");
        };
      };
    }

    private static Jwt jwt(String token, String role) {
      return Jwt.withTokenValue(token)
          .header("alg", "none")
          .subject("service-account-" + role)
          .claim("roles", List.of(role))
          .build();
    }
  }

  // The threads every token check ran on, recorded by NetworkTokens.
  private static final Queue<String> TOKEN_CHECK_THREADS = new ConcurrentLinkedQueue<>();

  /** A card as the test issued it, with the PAN the Network would send. */
  private record Card(String cardToken, String pan, Instant expiresAt) {}

  @Autowired CardCopies copies;
  @Autowired CardHashing hashing;
  @Autowired DatabaseClient db;
  @Autowired MeterRegistry meters;
  @Autowired KafkaContainer kafka;
  @Autowired PostgreSQLContainer postgres;

  @LocalGrpcServerPort int port;

  private ManagedChannel channel;
  private KafkaProducer<String, String> producer;

  @BeforeEach
  void connect() {
    channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
    producer =
        new KafkaProducer<>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class));
  }

  @AfterEach
  void disconnect() {
    channel.shutdownNow();
    producer.close();
  }

  @Test
  void anActiveCardIsApprovedAndAuthorized() {
    var card = issuedCard();
    var request = chargeOn(card);
    var requestId = request.getRetrievalReferenceNumber();

    var response = authorize(request);

    assertThat(response.getRetrievalReferenceNumber()).isEqualTo(requestId);
    assertThat(response.getResponseCode()).isEqualTo("00");
    assertThat(response.getAuthorizationCode()).matches("[A-Z0-9]{6}");
    assertThat(statusOf(card)).isEqualTo(CardStatus.AUTHORIZED);
    assertThat(recorded(requestId))
        .containsEntry("card_token", card.cardToken())
        .containsEntry("amount_minor", AMOUNT)
        .containsEntry("currency", "USD")
        .containsEntry("acceptor_id", ACCEPTOR_ID)
        .containsEntry("response_code", "00")
        .containsEntry("authorization_code", response.getAuthorizationCode())
        .containsEntry("status", "APPROVED");
  }

  // The card's events, in the order they reached the topic: its card on file, then the approved
  // Authorization.
  @Test
  void anApprovedAuthorizationIsPublishedWithWhatThePlatformNeedsToMatchItsClearing() {
    var card = issuedCard();
    var request = chargeOn(card);
    var requestId = request.getRetrievalReferenceNumber();
    var response = authorize(request);

    var records = authorizationsOf(card, 2);

    assertThat(records)
        .extracting(AuthorizationsTopic::eventType)
        .containsExactly("CardOnFile", "AuthorizationApproved");
    assertThat(records.get(1).value())
        .isEqualTo(authorizationJson(requestId, card, response.getAuthorizationCode()));
  }

  // Had the decline written an event, it would have reached the card's partition before the
  // approved Authorization that follows it.
  @Test
  void aDeclineIsNotPublished() {
    var card = issuedCard();
    assertThat(authorize(chargeOn(card).setAmountMinor(AMOUNT + 1)).getResponseCode())
        .isEqualTo("61");
    var approved = chargeOn(card);
    var response = authorize(approved);

    var records = authorizationsOf(card, 2);

    assertThat(records)
        .extracting(AuthorizationsTopic::eventType)
        .containsExactly("CardOnFile", "AuthorizationApproved");
    assertThat(records.get(1).value())
        .isEqualTo(
            authorizationJson(
                approved.getRetrievalReferenceNumber(), card, response.getAuthorizationCode()));
  }

  @Test
  void anUnknownPanIsDeclinedAsAnInvalidCard() {
    var requestId = UUID.randomUUID().toString();

    var response =
        authorize(
            AuthorizationRequest.newBuilder()
                .setPrimaryAccountNumber("4000000000000002")
                .setSecurityCode(SECURITY_CODE)
                .setExpirationDate("2611")
                .setAmountMinor(AMOUNT)
                .setCurrencyCode("USD")
                .setRetrievalReferenceNumber(requestId)
                .setCardAcceptorId(ACCEPTOR_ID));

    assertThat(response.getResponseCode()).isEqualTo("14");
    assertThat(response.getAuthorizationCode()).isEmpty();
    assertThat(recorded(requestId))
        .containsEntry("card_token", null)
        .containsEntry("amount_minor", AMOUNT)
        .containsEntry("response_code", "14")
        .containsEntry("authorization_code", null)
        .containsEntry("status", "DECLINED");
  }

  @Test
  void aCancelledCardIsDeclinedAndStaysCancelled() {
    var card = issuedCard();
    send(card.cardToken(), "CardCancelled", "{\"cardToken\":\"" + card.cardToken() + "\"}");
    awaitStatus(card, CardStatus.CANCELLED);

    var response = authorize(chargeOn(card));

    assertThat(response.getResponseCode()).isEqualTo("54");
    assertThat(statusOf(card)).isEqualTo(CardStatus.CANCELLED);
  }

  // As the platform declined a Charge on a CLEARED card: a Single-Use Card takes one Charge.
  @Test
  void aClearedCardIsDeclinedAsNotPermittedAndStaysCleared() {
    var card = issuedCard();
    send(card.cardToken(), "CardCleared", "{\"cardToken\":\"" + card.cardToken() + "\"}");
    awaitStatus(card, CardStatus.CLEARED);

    var response = authorize(chargeOn(card));

    assertThat(response.getResponseCode()).isEqualTo("57");
    assertThat(statusOf(card)).isEqualTo(CardStatus.CLEARED);
  }

  @Test
  void aCardCopiedWithoutAnAcceptorIdIsDeclinedAsNotPermitted() {
    var card = issuedCard(null);

    var response = authorize(chargeOn(card));

    assertThat(response.getResponseCode()).isEqualTo("57");
    assertThat(statusOf(card)).isEqualTo(CardStatus.ACTIVE);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          # Charge                                | expiry | amount  | currency | security code | acceptor ID     | answer
          the wrong expiry month                  | 2001   | 1240000 | USD      | 123           | GLOBEX000000001 | 54
          a wrong security code                   |        | 1240000 | USD      | 999           | GLOBEX000000001 | N7
          another Acceptor ID                     |        | 1240000 | USD      | 123           | OTHER0000000002 | 57
          one minor unit above the card's amount  |        | 1240001 | USD      | 123           | GLOBEX000000001 | 61
          another currency                        |        | 1240000 | EUR      | 123           | GLOBEX000000001 | 61
          a currency that isn't ISO 4217          |        | 1240000 | DOLLARS  | 123           | GLOBEX000000001 | 61
          a zero amount                           |        | 0       | USD      | 123           | GLOBEX000000001 | 13
          """)
  void aDeclineIsRecordedAndLeavesTheCardActive(
      String charge,
      String expirationDate,
      long amountMinor,
      String currencyCode,
      String securityCode,
      String acceptorId,
      String answer) {
    var card = issuedCard();
    var request =
        chargeOn(card)
            .setAmountMinor(amountMinor)
            .setCurrencyCode(currencyCode)
            .setSecurityCode(securityCode)
            .setCardAcceptorId(acceptorId);
    if (expirationDate != null) {
      request.setExpirationDate(expirationDate);
    }

    var response = authorize(request);

    assertThat(response.getResponseCode()).isEqualTo(answer);
    assertThat(response.getAuthorizationCode()).isEmpty();
    assertThat(statusOf(card)).isEqualTo(CardStatus.ACTIVE);
    assertThat(recorded(request.getRetrievalReferenceNumber()))
        .containsEntry("card_token", card.cardToken())
        .containsEntry("amount_minor", amountMinor)
        .containsEntry("currency", currencyCode)
        .containsEntry("acceptor_id", acceptorId)
        .containsEntry("response_code", answer)
        .containsEntry("authorization_code", null)
        .containsEntry("status", "DECLINED");
  }

  @Test
  void aSecondChargeOnAnApprovedCardIsDeclinedAndTheCardStaysAuthorized() {
    var card = issuedCard();
    assertThat(authorize(chargeOn(card)).getResponseCode()).isEqualTo("00");
    var second = chargeOn(card);

    var response = authorize(second);

    assertThat(response.getResponseCode()).isEqualTo("57");
    assertThat(statusOf(card)).isEqualTo(CardStatus.AUTHORIZED);
    assertThat(recorded(second.getRetrievalReferenceNumber()))
        .containsEntry("response_code", "57")
        .containsEntry("status", "DECLINED");
  }

  @Test
  void aResentRequestGetsTheFirstAnswerWhateverTheRulesWouldSayNow() {
    var card = issuedCard();
    var request = chargeOn(card);
    var first = authorize(request);

    // The card is AUTHORIZED now and the amount above the card's: both would decline a new Charge.
    var resent = authorize(request.setAmountMinor(AMOUNT + 1));

    assertThat(first.getResponseCode()).isEqualTo("00");
    assertThat(resent).isEqualTo(first);
    assertThat(recordedCount(request.getRetrievalReferenceNumber())).isOne();
    assertThat(recorded(request.getRetrievalReferenceNumber()))
        .containsEntry("amount_minor", AMOUNT);
  }

  @Test
  void requestsWithOneRequestIdAtOnceRecordOneAuthorizationAndAllGetItsAnswer() throws Exception {
    var card = issuedCard();
    var request = chargeOn(card).build();

    var answers = atOnce(List.of(request, request, request, request, request, request));

    assertThat(answers).allSatisfy(answer -> assertThat(answer).isEqualTo(answers.getFirst()));
    assertThat(answers.getFirst().getResponseCode()).isEqualTo("00");
    assertThat(recordedCount(request.getRetrievalReferenceNumber())).isOne();
    assertThat(statusOf(card)).isEqualTo(CardStatus.AUTHORIZED);
  }

  @Test
  void ofChargesWithDifferentRequestIdsAtOnceOnOneCardExactlyOneIsApproved() throws Exception {
    var card = issuedCard();
    var requests = new ArrayList<AuthorizationRequest>();
    for (var i = 0; i < 8; i++) {
      requests.add(chargeOn(card).build());
    }

    var answers = atOnce(requests);

    assertThat(answers)
        .extracting(AuthorizationResponse::getResponseCode)
        .containsOnlyOnce("00")
        .containsOnly("00", "57");
    for (var answer : answers) {
      assertThat(recorded(answer.getRetrievalReferenceNumber()))
          .containsEntry("response_code", answer.getResponseCode());
    }
    assertThat(statusOf(card)).isEqualTo(CardStatus.AUTHORIZED);
  }

  @Test
  void aRequestWithoutARetrievalReferenceNumberIsRefusedAndNothingIsRecorded() {
    var card = issuedCard();

    assertThat(grpcStatusOf(() -> authorize(chargeOn(card).clearRetrievalReferenceNumber())))
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(statusOf(card)).isEqualTo(CardStatus.ACTIVE);
    assertThat(recordedCount("")).isZero();
  }

  @Test
  void aCallWithoutTheNetworksTokenIsRefused() {
    var card = issuedCard();
    var request = chargeOn(card).build();
    var stub =
        IssuerAuthorizationGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);

    assertThat(grpcStatusOf(() -> stub.authorize(request))).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(
            grpcStatusOf(
                () ->
                    stub.withInterceptors(new BearerTokenAuthenticationInterceptor("buyer-token"))
                        .authorize(request)))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(statusOf(card)).isEqualTo(CardStatus.ACTIVE);
    assertThat(recordedCount(request.getRetrievalReferenceNumber())).isZero();
  }

  // The Authorization dashboard reads the counter; Spring gRPC's observation times every call.
  @Test
  void everyAnswerIsCountedByItsResponseCodeAndEveryCallIsTimed() {
    var card = issuedCard();
    var approvedBefore = answersCounted("00");
    var declinedBefore = answersCounted("57");

    authorize(chargeOn(card));
    authorize(chargeOn(card));

    assertThat(answersCounted("00")).isEqualTo(approvedBefore + 1);
    assertThat(answersCounted("57")).isEqualTo(declinedBefore + 1);
    assertThat(meters.find("grpc.server").tag("rpc.method", "Authorize").timer().count())
        .isPositive();
  }

  /**
   * Tests that hold the database on purpose, to order a race, under a time limit long enough that
   * the held Charge isn't answered 91 meanwhile (DatabaseFailures).
   */
  @Nested
  @TestPropertySource(properties = LONG_TIME_LIMIT)
  class HeldOnLocks {

    // The Charge reads the card ACTIVE, then CardCleared commits before it moves the card: a lock
    // on
    // the card's row queues CardCleared's write first and the Charge's move behind it, so the move
    // finds the card CLEARED and the Charge is declined.
    @Test
    void aCardClearedWhileItsChargeIsMovingItIsDeclinedAndStaysCleared() throws Exception {
      var card = issuedCard();
      var charge = chargeOn(card).build();

      try (var network = Executors.newSingleThreadExecutor();
          var lock = connection()) {
        lock.setAutoCommit(false);
        try (var statement =
            lock.prepareStatement("select 1 from card_copy where card_token = ? for update")) {
          statement.setString(1, card.cardToken());
          statement.executeQuery().close();
        }
        send(card.cardToken(), "CardCleared", "{\"cardToken\":\"" + card.cardToken() + "\"}");
        await().atMost(Duration.ofSeconds(10)).until(() -> lockWaiters() >= 1);
        var late = network.submit(() -> authorize(charge));
        await().atMost(Duration.ofSeconds(5)).until(() -> lockWaiters() >= 2);

        lock.rollback();

        assertThat(late.get(10, TimeUnit.SECONDS).getResponseCode()).isEqualTo("57");
      }
      assertThat(statusOf(card)).isEqualTo(CardStatus.CLEARED);
      assertThat(recorded(charge.getRetrievalReferenceNumber()))
          .containsEntry("response_code", "57")
          .containsEntry("status", "DECLINED");
    }

    // The proof that the gRPC handler only hands each call to Reactor: Charges stuck on a locked
    // table outnumber the gRPC call threads, yet a Reversal still gets its answer. A handler that
    // waited for the database would hold every call thread, and the Reversal would never start.
    @Test
    void callsWaitingOnTheDatabaseHoldNoThreadSoAnotherCallIsStillAnswered() throws Exception {
      var held = GrpcCallThreads.THREADS + 1;
      TOKEN_CHECK_THREADS.clear();
      var requests = new ArrayList<AuthorizationRequest>();
      for (var i = 0; i < held; i++) {
        requests.add(chargeOn(issuedCard()).build());
      }

      // Fewer held Charges than R2DBC's pool has connections (10 by default), so one is left for
      // the
      // Reversal. Closed in reverse order: the lock's connection rolls back before the executor
      // waits.
      try (var network = Executors.newFixedThreadPool(held);
          var lock = connection()) {
        lock.setAutoCommit(false);
        try (var statement = lock.createStatement()) {
          statement.execute("lock table card_copy in access exclusive mode");
        }
        var answers = new ArrayList<Future<AuthorizationResponse>>();
        for (var request : requests) {
          answers.add(network.submit(() -> authorize(request)));
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> lockWaiters() >= held);

        var requestId = UUID.randomUUID().toString();
        assertThat(reverse(requestId)).isEqualTo(acknowledgement(requestId));
        assertThat(answers).noneMatch(Future::isDone);
        // Every call ran on the fixed pool: with gRPC's unbounded default the Reversal would be
        // answered whatever the handler did, and the test would prove nothing.
        assertThat(TOKEN_CHECK_THREADS)
            .hasSize(held + 1)
            .allMatch(thread -> thread.startsWith("grpc-call-"));

        lock.rollback();
        for (var answer : answers) {
          assertThat(answer.get(10, TimeUnit.SECONDS).getResponseCode()).isEqualTo("00");
        }
      }
    }
  }

  /**
   * The Network cancels an Authorization by its request ID. Every Reversal is acknowledged with
   * {@code 00}; only an approved Authorization changes, and its card goes back to ACTIVE.
   */
  @Nested
  @TestPropertySource(properties = LONG_TIME_LIMIT)
  class Reversals {

    @Test
    void anApprovedAuthorizationIsReversedAndItsCardCanBeChargedAgain() {
      var card = issuedCard();
      var charge = chargeOn(card);
      var requestId = charge.getRetrievalReferenceNumber();
      var approval = authorize(charge);
      assertThat(approval.getResponseCode()).isEqualTo("00");

      var response = reverse(requestId);

      assertThat(response).isEqualTo(acknowledgement(requestId));
      assertThat(recorded(requestId))
          .containsEntry("response_code", "00")
          .containsEntry("authorization_code", approval.getAuthorizationCode())
          .containsEntry("status", "REVERSED");
      assertThat(statusOf(card)).isEqualTo(CardStatus.ACTIVE);
      assertThat(authorize(chargeOn(card)).getResponseCode()).isEqualTo("00");
    }

    @Test
    void theReversalOfAnApprovedAuthorizationIsPublishedAfterItAndOnlyOnce() {
      var card = issuedCard();
      var charge = chargeOn(card);
      var requestId = charge.getRetrievalReferenceNumber();
      var approved = authorize(charge);
      reverse(requestId);
      reverse(requestId);
      var next = chargeOn(card);
      var nextApproved = authorize(next);

      var records = authorizationsOf(card, 4);

      assertThat(records)
          .extracting(AuthorizationsTopic::eventType)
          .containsExactly(
              "CardOnFile",
              "AuthorizationApproved",
              "AuthorizationReversed",
              "AuthorizationApproved");
      var reversed = authorizationJson(requestId, card, approved.getAuthorizationCode());
      assertThat(records.get(2).value()).isEqualTo(reversed);
      assertThat(records.get(3).value())
          .isEqualTo(
              authorizationJson(
                  next.getRetrievalReferenceNumber(), card, nextApproved.getAuthorizationCode()));
    }

    // The Network may still reverse an Authorization whose Charge already cleared: the
    // Reversal is acknowledged, but the cleared card takes no new Charge.
    @Test
    void aReversalAfterItsCardClearedLeavesTheCardClearedAndTheNextChargeIsDeclined() {
      var card = issuedCard();
      var charge = chargeOn(card);
      assertThat(authorize(charge).getResponseCode()).isEqualTo("00");
      send(card.cardToken(), "CardCleared", "{\"cardToken\":\"" + card.cardToken() + "\"}");
      awaitStatus(card, CardStatus.CLEARED);

      var requestId = charge.getRetrievalReferenceNumber();
      assertThat(reverse(requestId)).isEqualTo(acknowledgement(requestId));

      assertThat(statusOf(card)).isEqualTo(CardStatus.CLEARED);
      assertThat(authorize(chargeOn(card)).getResponseCode()).isEqualTo("57");
    }

    @Test
    void aDeclinedAuthorizationIsAcknowledgedTwiceAndNothingChanges() {
      var card = issuedCard();
      var charge = chargeOn(card).setAmountMinor(AMOUNT + 1);
      var requestId = charge.getRetrievalReferenceNumber();
      assertThat(authorize(charge).getResponseCode()).isEqualTo("61");

      assertThat(reverse(requestId)).isEqualTo(acknowledgement(requestId));
      assertThat(reverse(requestId)).isEqualTo(acknowledgement(requestId));

      assertThat(recorded(requestId)).containsEntry("status", "DECLINED");
      assertThat(statusOf(card)).isEqualTo(CardStatus.ACTIVE);
    }

    // Only the reversed Authorization's own record decides: reversing A again mustn't free the
    // card that B now holds.
    @Test
    void aRepeatedReversalChangesNothingMore() {
      var card = issuedCard();
      var first = chargeOn(card);
      var firstRequestId = first.getRetrievalReferenceNumber();
      assertThat(authorize(first).getResponseCode()).isEqualTo("00");
      reverse(firstRequestId);
      var second = chargeOn(card);
      assertThat(authorize(second).getResponseCode()).isEqualTo("00");

      assertThat(reverse(firstRequestId)).isEqualTo(acknowledgement(firstRequestId));

      assertThat(recorded(second.getRetrievalReferenceNumber()))
          .containsEntry("status", "APPROVED");
      assertThat(statusOf(card)).isEqualTo(CardStatus.AUTHORIZED);
    }

    @Test
    void aReversalBeforeItsAuthorizationMakesTheLateAuthorizationAnswerDoNotHonour() {
      var card = issuedCard();
      var charge = chargeOn(card);
      var requestId = charge.getRetrievalReferenceNumber();

      assertThat(reverse(requestId)).isEqualTo(acknowledgement(requestId));
      assertThat(reverse(requestId)).isEqualTo(acknowledgement(requestId));
      var late = authorize(charge);

      assertThat(late.getResponseCode()).isEqualTo("05");
      assertThat(late.getAuthorizationCode()).isEmpty();
      assertThat(statusOf(card)).isEqualTo(CardStatus.ACTIVE);
      assertThat(recordedCount(requestId)).isOne();
      assertThat(recorded(requestId))
          .containsEntry("card_token", null)
          .containsEntry("response_code", "05")
          .containsEntry("status", "REVERSED");
    }

    // The Reversal commits while the Authorization is running: a lock on the card copy holds it
    // before it reads the card, so it claims its request ID only afterwards.
    @Test
    void anAuthorizationStillRunningWhenItsReversalCommitsAnswersDoNotHonour() throws Exception {
      var card = issuedCard();
      var charge = chargeOn(card).build();
      var requestId = charge.getRetrievalReferenceNumber();

      try (var network = Executors.newSingleThreadExecutor();
          var lock = connection()) {
        lock.setAutoCommit(false);
        try (var statement = lock.createStatement()) {
          statement.execute("lock table card_copy in access exclusive mode");
        }
        var late = network.submit(() -> authorize(charge));
        await().atMost(Duration.ofSeconds(5)).until(() -> lockWaiters() >= 1);

        var reversal = reverse(requestId);
        lock.rollback();

        assertThat(reversal).isEqualTo(acknowledgement(requestId));
        assertThat(late.get(10, TimeUnit.SECONDS).getResponseCode()).isEqualTo("05");
      }
      assertThat(statusOf(card)).isEqualTo(CardStatus.ACTIVE);
      assertThat(recorded(requestId))
          .containsEntry("card_token", null)
          .containsEntry("status", "REVERSED");
    }

    // The Authorization claims its request ID first and approves after the Reversal arrived: a
    // lock on the card's row holds it after it recorded its approval, before it moved the card.
    // The Reversal's claim waits for it to commit, then reverses it.
    @Test
    void anAuthorizationApprovedAfterItsReversalArrivedIsReversed() throws Exception {
      var card = issuedCard();
      var charge = chargeOn(card).build();
      var requestId = charge.getRetrievalReferenceNumber();

      try (var network = Executors.newFixedThreadPool(2);
          var lock = connection()) {
        lock.setAutoCommit(false);
        try (var statement =
            lock.prepareStatement("select 1 from card_copy where card_token = ? for update")) {
          statement.setString(1, card.cardToken());
          statement.executeQuery().close();
        }
        var slow = network.submit(() -> authorize(charge));
        await().atMost(Duration.ofSeconds(5)).until(() -> lockWaiters() >= 1);
        var reversal = network.submit(() -> reverse(requestId));
        await().atMost(Duration.ofSeconds(5)).until(() -> lockWaiters() >= 2);

        lock.rollback();

        assertThat(slow.get(10, TimeUnit.SECONDS).getResponseCode()).isEqualTo("00");
        assertThat(reversal.get(10, TimeUnit.SECONDS)).isEqualTo(acknowledgement(requestId));
      }
      assertThat(statusOf(card)).isEqualTo(CardStatus.ACTIVE);
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
  }

  /**
   * The service's database is slow or down (#93): the Network gets {@code 91} (Issuer unavailable)
   * before its 1-second deadline, so it never times out and sends a Reversal, and the 91 leaves no
   * approval standing. The circuit breaker opens while the database is down and closes once it is
   * back, with no restart. On the application's own settings.
   */
  @Nested
  class DatabaseFailures {

    // The simulator's Network gives each call 1 second, then declines and sends a Reversal.
    private static final Duration NETWORK_DEADLINE = Duration.ofSeconds(1);
    // A call the open breaker refuses never waits for the database.
    private static final Duration AT_ONCE = Duration.ofMillis(50);

    @Test
    void aChargeStillWaitingAtTheTimeLimitAnswers91BeforeTheNetworksDeadlineAndIsReversed()
        throws Exception {
      var card = issuedCard();
      var charge = chargeOn(card).build();
      var requestId = charge.getRetrievalReferenceNumber();

      try (var lock = connection()) {
        lock.setAutoCommit(false);
        try (var statement = lock.createStatement()) {
          statement.execute("lock table card_copy in access exclusive mode");
        }
        var started = System.nanoTime();

        var response = authorize(charge);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(NETWORK_DEADLINE);
        assertThat(response).isEqualTo(issuerUnavailable(requestId));
        lock.rollback();
      }
      // Nothing committed, so the service's own Reversal claimed the request ID: a resend answers
      // 05 and moves no card.
      awaitNoApprovalStanding(card, requestId);
      assertThat(recorded(requestId)).containsEntry("response_code", "05");
      assertThat(outboxCount(card, "AuthorizationApproved")).isZero();
    }

    @Test
    void whileTheDatabaseIsFrozenChargesAnswer91AndTheBreakerOpensThenClosesOnceItThaws()
        throws SQLException {
      var docker = postgres.getDockerClient();

      answers91ThenRecovers(
          () -> docker.pauseContainerCmd(postgres.getContainerId()).exec(),
          () -> docker.unpauseContainerCmd(postgres.getContainerId()).exec());
    }

    // The database stopped rather than stuck: every connection is ended and new ones are refused,
    // so the pool has to reconnect once it is back.
    @Test
    void whileTheDatabaseRefusesConnectionsChargesAnswer91AndThePoolReconnectsOnceItIsBack()
        throws SQLException {
      // Connected to the server's postgres database: PostgreSQL won't refuse connections to the
      // database the statement runs in.
      try (var admin =
          DriverManager.getConnection(
              postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/postgres"),
              postgres.getUsername(),
              postgres.getPassword())) {
        answers91ThenRecovers(
            () -> refuseConnections(admin, true), () -> refuseConnections(admin, false));
      }
    }

    // The time limit cancels the Charge while PostgreSQL waits to confirm its commit, which has
    // already happened: the commit can't be taken back. A synchronous standby that never comes
    // holds every commit there. The Network got 91, so the service reverses the Authorization
    // itself, and the card can be charged again.
    @Test
    void aChargeCommittedAfterItsTimeLimitIsReversedAndItsCardCanBeChargedAgain() throws Exception {
      var card = issuedCard();
      var charge = chargeOn(card).build();
      var requestId = charge.getRetrievalReferenceNumber();

      AuthorizationResponse response;
      try (var admin = connection()) {
        holdCommits(admin, true);
        try {
          response = authorize(charge);
        } finally {
          holdCommits(admin, false);
        }
      }

      assertThat(response).isEqualTo(issuerUnavailable(requestId));
      awaitNoApprovalStanding(card, requestId);
      assertThat(recorded(requestId)).containsEntry("response_code", "00");
      assertThat(outboxCount(card, "AuthorizationReversed")).isOne();
      assertThat(authorize(chargeOn(card)).getResponseCode()).isEqualTo("00");
    }

    // Charges on a card answer 91 while the database is down, the breaker opens and then answers
    // at once, every 91 is counted, and a Reversal fails quickly; once the database is back, the
    // breaker closes, a Charge is
    // approved, and none of the 91s left an approval standing.
    private void answers91ThenRecovers(DatabaseSwitch down, DatabaseSwitch up) throws SQLException {
      var card = issuedCard();
      var unavailableBefore = answersCounted("91");
      var answered91 = new ArrayList<String>();

      down.flip();
      try {
        await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ZERO)
            .until(
                () -> {
                  var charge = chargeOn(card).build();
                  answered91.add(charge.getRetrievalReferenceNumber());
                  assertThat(authorize(charge).getResponseCode()).isEqualTo("91");
                  return breakerIs(CircuitBreaker.State.OPEN);
                });
        var charge = chargeOn(card).build();
        answered91.add(charge.getRetrievalReferenceNumber());
        var started = System.nanoTime();

        var refused = authorize(charge);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(AT_ONCE);
        assertThat(refused).isEqualTo(issuerUnavailable(charge.getRetrievalReferenceNumber()));
        assertThat(answersCounted("91")).isEqualTo(unavailableBefore + answered91.size());
        // A Reversal has no breaker but the same time limit: a quick error for the Network to
        // resend on, before its deadline.
        var reversalStarted = System.nanoTime();
        assertThat(grpcStatusOf(() -> reverse(UUID.randomUUID().toString())))
            .isEqualTo(Status.Code.UNKNOWN);
        assertThat(Duration.ofNanos(System.nanoTime() - reversalStarted))
            .isLessThan(NETWORK_DEADLINE);
      } finally {
        up.flip();
      }

      // Once the breaker lets calls through again, the database answers them and it closes.
      await()
          .atMost(Duration.ofSeconds(30))
          .pollInterval(Duration.ofMillis(200))
          .until(() -> authorize(chargeOn(issuedCard())).getResponseCode().equals("00"));
      await()
          .atMost(Duration.ofSeconds(10))
          .until(
              () -> {
                authorize(chargeOn(issuedCard()));
                return breakerIs(CircuitBreaker.State.CLOSED);
              });
      for (var requestId : answered91) {
        awaitNoApprovalStanding(card, requestId);
      }
    }

    /** Takes the database down, or brings it back. */
    private interface DatabaseSwitch {
      void flip() throws SQLException;
    }

    // No row (the open breaker refused it) or a REVERSED one, and the card ACTIVE.
    private void awaitNoApprovalStanding(Card card, String requestId) {
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () ->
                  (recordedCount(requestId) == 0
                          || "REVERSED".equals(recorded(requestId).get("status")))
                      && statusOf(card) == CardStatus.ACTIVE);
    }

    private boolean breakerIs(CircuitBreaker.State state) {
      var gauge =
          meters
              .find("resilience4j.circuitbreaker.state")
              .tag("name", IssuerAuthorizationService.DATABASE)
              .tag("state", state.name().toLowerCase(Locale.ROOT))
              .gauge();
      return gauge != null && gauge.value() == 1;
    }

    // Stands in for the database going down: it ends every connection to the service's database
    // and accepts no new one until allowed again.
    private void refuseConnections(Connection admin, boolean refuse) throws SQLException {
      var database = postgres.getDatabaseName();
      try (var statement = admin.createStatement()) {
        statement.execute("alter database " + database + " allow_connections " + !refuse);
        if (refuse) {
          statement.execute(
              "select pg_terminate_backend(pid) from pg_stat_activity where datname = '"
                  + database
                  + "'");
        }
      }
    }

    private static void holdCommits(Connection admin, boolean hold) throws SQLException {
      try (var statement = admin.createStatement()) {
        statement.execute(
            hold
                ? "alter system set synchronous_standby_names = 'never_connects'"
                : "alter system reset synchronous_standby_names");
        statement.execute("select pg_reload_conf()");
      }
    }

    private long outboxCount(Card card, String eventType) {
      return db.sql(
              "select count(*) as n from outbox where card_token = :card and event_type = :type")
          .bind("card", card.cardToken())
          .bind("type", eventType)
          .map(row -> row.get("n", Long.class))
          .one()
          .block();
    }

    private static AuthorizationResponse issuerUnavailable(String requestId) {
      return AuthorizationResponse.newBuilder()
          .setRetrievalReferenceNumber(requestId)
          .setResponseCode("91")
          .build();
    }
  }

  private List<ConsumerRecord<String, String>> authorizationsOf(Card card, int count) {
    return new AuthorizationsTopic(kafka).awaitRecords(card.cardToken(), count);
  }

  // An approved or reversed Authorization's event, as the platform will read it: the Charge as the
  // Network sent it, the card it's on and the authorization code it was approved with.
  private static String authorizationJson(String requestId, Card card, String authorizationCode) {
    return """
        {"requestId":"%s","cardToken":"%s","amountMinorUnits":%d,"currency":"USD",\
        "authorizationCode":"%s","acceptorId":"%s"}"""
        .formatted(requestId, card.cardToken(), AMOUNT, authorizationCode, ACCEPTOR_ID);
  }

  // Sends every request at once, each from its own thread, and returns the answers in order.
  private List<AuthorizationResponse> atOnce(List<AuthorizationRequest> requests) throws Exception {
    var start = new CountDownLatch(1);
    var futures = new ArrayList<Future<AuthorizationResponse>>();
    try (var network = Executors.newFixedThreadPool(requests.size())) {
      for (var request : requests) {
        futures.add(
            network.submit(
                () -> {
                  start.await();
                  return authorize(request);
                }));
      }
      start.countDown();
      var answers = new ArrayList<AuthorizationResponse>();
      for (var future : futures) {
        answers.add(future.get(10, TimeUnit.SECONDS));
      }
      return answers;
    }
  }

  private Card issuedCard() {
    return issuedCard(ACCEPTOR_ID);
  }

  // Issued as the platform issues it, on the cards topic, with the PAN's and security code's
  // hashes under the shared hash key; returns once the service holds its copy.
  private Card issuedCard(String acceptorId) {
    var card =
        new Card(
            UUID.randomUUID().toString(),
            "990000%010d".formatted(PANS.incrementAndGet()),
            Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS));
    send(
        card.cardToken(),
        "CardIssued",
        """
        {"cardToken":"%s","panHash":"%s","securityCodeHash":"%s","expiresAt":"%s",
         "amountMinorUnits":%d,"currency":"USD","acceptorId":%s}
        """
            .formatted(
                card.cardToken(),
                hashing.hash(card.pan()),
                hashing.hash(SECURITY_CODE),
                card.expiresAt(),
                AMOUNT,
                acceptorId == null ? "null" : "\"" + acceptorId + "\""));
    awaitStatus(card, CardStatus.ACTIVE);
    return card;
  }

  private void send(String cardToken, String eventType, String json) {
    var record = new ProducerRecord<>("cards", cardToken, json);
    record.headers().add("event-type", eventType.getBytes(StandardCharsets.UTF_8));
    producer.send(record);
    producer.flush();
  }

  private void awaitStatus(Card card, CardStatus status) {
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(statusOf(card)).isEqualTo(status));
  }

  private CardStatus statusOf(Card card) {
    var copy = copies.find(card.cardToken()).block();
    return copy == null ? null : copy.status();
  }

  // Everything the Network would send for a Charge of the card's whole amount, under a new
  // request ID.
  private AuthorizationRequest.Builder chargeOn(Card card) {
    return AuthorizationRequest.newBuilder()
        .setPrimaryAccountNumber(card.pan())
        .setSecurityCode(SECURITY_CODE)
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

  // Signed in as the Network (NetworkTokens), with a deadline so a hang fails the test quickly.
  private IssuerAuthorizationBlockingStub network() {
    return IssuerAuthorizationGrpc.newBlockingStub(channel)
        .withDeadlineAfter(5, TimeUnit.SECONDS)
        .withInterceptors(new BearerTokenAuthenticationInterceptor("network-token"));
  }

  // The gRPC status of a call refused before any answer.
  private static Status.Code grpcStatusOf(ThrowingCallable call) {
    return assertThatExceptionOfType(StatusRuntimeException.class)
        .isThrownBy(call)
        .actual()
        .getStatus()
        .getCode();
  }

  // The test context's registry outlives each test, so a test compares counts before and after.
  private double answersCounted(String responseCode) {
    var counter = meters.find("authorization.answers").tag("response.code", responseCode).counter();
    return counter == null ? 0 : counter.count();
  }

  private Map<String, Object> recorded(String requestId) {
    return db.sql(
            """
            select card_token, amount_minor, currency, acceptor_id, response_code,
                   authorization_code, status
              from authorizations
             where request_id = :requestId
            """)
        .bind("requestId", requestId)
        .fetch()
        .one()
        .block();
  }

  private long recordedCount(String requestId) {
    return db.sql("select count(*) as n from authorizations where request_id = :requestId")
        .bind("requestId", requestId)
        .map(row -> row.get("n", Long.class))
        .one()
        .block();
  }

  // Connections to the test database waiting for a lock: the calls a test holds up.
  private long lockWaiters() {
    return db.sql(
            """
            select count(*) as n from pg_stat_activity
             where datname = current_database() and wait_event_type = 'Lock'
            """)
        .map(row -> row.get("n", Long.class))
        .one()
        .block();
  }

  private Connection connection() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }
}
