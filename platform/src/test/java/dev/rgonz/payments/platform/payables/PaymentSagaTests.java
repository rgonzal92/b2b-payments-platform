package dev.rgonz.payments.platform.payables;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import dev.rgonz.payments.platform.TestKafka;
import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.cards.CardExpired;
import dev.rgonz.payments.platform.cards.CardExpiryJob;
import dev.rgonz.payments.platform.cards.CardIssued;
import dev.rgonz.payments.platform.cards.Cards;
import dev.rgonz.payments.platform.cards.SingleUseCard;
import dev.rgonz.payments.platform.ledger.Holds;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import dev.rgonz.payments.platform.suppliers.RemittanceAdvices;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.modulith.test.PublishedEventsExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

/**
 * An approved Invoice's Payment runs through the saga: over HTTP with mocked tokens, with the
 * worker and the card expiry job driven by hand instead of on their schedules. authorization-
 * service's "card on file" arrives on a real Kafka's {@code authorizations} topic in the tests of
 * the wait; the others confirm each card by hand as soon as it is issued.
 */
@Import({TestcontainersConfiguration.class, TestKafka.class})
@SpringBootTest(
    properties = {
      "payables.payment-worker.enabled=false",
      "cards.expiry-job.enabled=false",
      "spring.kafka.listener.auto-startup=true"
    })
@AutoConfigureMockMvc
@AutoConfigureMockRestServiceServer
class PaymentSagaTests {

  // High enough that one approval completes every Invoice here.
  private static final long APPROVAL_THRESHOLD = 100_000_000;
  // Every Invoice here names this remittance address, which supplier stands in for.
  private static final String GLOBEX = "https://globex.example/remittance-advice";

  @Autowired MockMvcTester mvc;
  @Autowired PaymentSaga saga;
  @Autowired TransactionTemplate transactions;
  @Autowired ApplicationEventPublisher events;
  @Autowired CompletedEventPublications completedEvents;
  @Autowired JdbcClient jdbc;
  @Autowired MockRestServiceServer supplier;
  @Autowired ApplicationContext context;
  @Autowired MeterRegistry meters;
  @Autowired KafkaContainer kafka;
  @Autowired Payments payments;
  @Autowired WaitingPaymentsMetric waitingMetric;
  @MockitoSpyBean Holds holds;
  @MockitoSpyBean Cards cards;
  @MockitoSpyBean RemittanceAdvices remittanceAdvices;

  @Test
  void anApprovedInvoicesPaymentPlacesAHoldThenIssuesASingleUseCardShownWithoutItsPan()
      throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    supplierAnswers(withSuccess());
    var now = Instant.now();

    runDueStepsWithCardsOnFile(acme, now);

    var body = invoice(acme, invoiceId).getResponse().getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.payment.state")).isEqualTo("COMPLETED");
    // Only what is safe to show: no PAN, no security code.
    assertThat(JsonPath.<Map<String, Object>>read(body, "$.payment.card"))
        .containsOnlyKeys("cardToken", "lastFour", "expiresAt");
    // At least 128 random bits, base64url-encoded.
    assertThat(JsonPath.<String>read(body, "$.payment.card.cardToken"))
        .matches("[A-Za-z0-9_-]{22,}");
    assertThat(JsonPath.<String>read(body, "$.payment.card.lastFour")).matches("\\d{4}");
    assertThat(Instant.parse(JsonPath.read(body, "$.payment.card.expiresAt")))
        .isEqualTo(now.plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.MICROS));
    assertThat(body).doesNotContainPattern("990000\\d{10}");
    assertThat(availableCredit(acme)).isEqualTo(8_760_000);
  }

  @Test
  void theRemittanceAdviceReachesTheSupplierWithAOneTimeLinkAndCompletesThePayment()
      throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    var deliveries = supplierAnswers(withSuccess());
    var now = Instant.now();

    runDueStepsWithCardsOnFile(acme, now);

    var paymentId = paymentIdOf(invoiceId).toString();
    assertThat(deliveries).hasSize(1);
    var advice = deliveries.getFirst().body();
    // The advice's ID is the Payment's, sent as the Idempotency-Key so the Supplier can drop
    // repeats.
    assertThat(deliveries.getFirst().idempotencyKey()).isEqualTo(paymentId);
    assertThat(JsonPath.<String>read(advice, "$.id")).isEqualTo(paymentId);
    assertThat(JsonPath.<String>read(advice, "$.invoiceId")).isEqualTo(invoiceId);
    assertThat(JsonPath.<String>read(advice, "$.invoiceNumber")).isEqualTo("INV-1");
    assertThat(JsonPath.<Map<String, Object>>read(advice, "$.amount"))
        .isEqualTo(Map.of("minorUnits", 1_240_000, "currency", "USD"));
    // A 256-bit token, base64url-encoded, under the platform's public URL.
    assertThat(JsonPath.<String>read(advice, "$.cardLink"))
        .matches("http://localhost:8080/v1/remittance-links/[A-Za-z0-9_-]{43}");
    assertThat(Instant.parse(JsonPath.read(advice, "$.cardLinkExpiresAt")))
        .isEqualTo(now.plus(Duration.ofDays(7)).truncatedTo(ChronoUnit.MICROS));
    assertThat(invoice(acme, invoiceId))
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"status": "APPROVED", "payment": {"state": "COMPLETED"}}
            """);
  }

  // The Payments dashboard reads these meters (grafana/dashboards/payments.json).
  @Test
  void eachStateAPaymentReachesIsCountedAndEachStepTimed() throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var states =
        List.of("STARTED", "HOLD_PLACED", "CARD_ISSUED", "CARD_ON_FILE", "COMPLETED", "FAILED");
    var steps = List.of("place-hold", "issue-card", "deliver-remittance-advice");
    var reachedBefore = states.stream().map(this::paymentsReached).toList();
    var timedBefore = steps.stream().map(this::stepsTimed).toList();
    approvedInvoice(acme, 1_240_000);
    supplierAnswers(withSuccess());

    runDueStepsWithCardsOnFile(acme, Instant.now());

    assertThat(states.stream().map(this::paymentsReached).toList())
        .isEqualTo(
            List.of(
                reachedBefore.get(0) + 1,
                reachedBefore.get(1) + 1,
                reachedBefore.get(2) + 1,
                reachedBefore.get(3) + 1,
                reachedBefore.get(4) + 1,
                reachedBefore.get(5)));
    assertThat(steps.stream().map(this::stepsTimed).toList())
        .isEqualTo(List.of(timedBefore.get(0) + 1, timedBefore.get(1) + 1, timedBefore.get(2) + 1));
  }

  @Test
  void aPaymentWaitsWithItsCardIssuedUntilTheServiceHasTheCardOnFileThenDeliversTheAdvice()
      throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    var deliveries = supplierAnswers(withSuccess());

    saga.runDueSteps(Instant.now());
    var stateWhileWaiting = paymentState(acme, invoiceId);
    var creditWhileWaiting = availableCredit(acme);
    var deliveriesWhileWaiting = deliveries.size();
    sendCardOnFile(cardTokenOf(invoiceId));

    awaitCompleted(acme, invoiceId);
    assertThat(stateWhileWaiting).isEqualTo("CARD_ISSUED");
    assertThat(creditWhileWaiting).isEqualTo(8_760_000);
    assertThat(deliveriesWhileWaiting).isZero();
    assertThat(deliveries).hasSize(1);
  }

  @Test
  void theWaitHasNoTimeLimitAndCountsNoAttempts() throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    // By a day later, other tests' Payments are due to retry their delivery: each fails its
    // attempt harmlessly. This one must not be delivered at all.
    supplier.expect(ExpectedCount.manyTimes(), requestTo(GLOBEX)).andRespond(withServerError());
    var issuedAt = Instant.now();

    saga.runDueSteps(issuedAt);
    saga.runDueSteps(issuedAt.plus(Duration.ofDays(1)));

    var payment = payments.findForInvoice(UUID.fromString(invoiceId)).orElseThrow();
    assertThat(payment.state()).isEqualTo(Payment.State.CARD_ISSUED);
    assertThat(payment.attempts()).isZero();
    assertThat(payment.nextAttemptAt()).isNull();
    assertThat(advicesFor(invoiceId)).isZero();
  }

  @Test
  void theSameCardOnFileTwiceDeliversTheAdviceOnce() throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    // A second delivery would fail the test.
    var deliveries = supplierAnswers(withSuccess());
    saga.runDueSteps(Instant.now());
    sendCardOnFile(cardTokenOf(invoiceId));
    awaitCompleted(acme, invoiceId);

    // The same event again, as Kafka redelivers one whose offset wasn't committed.
    sendCardOnFile(cardTokenOf(invoiceId));
    awaitAuthorizationsTopicConsumed();
    saga.runDueSteps(Instant.now());

    assertThat(paymentState(acme, invoiceId)).isEqualTo("COMPLETED");
    assertThat(deliveries).hasSize(1);
  }

  @Test
  void aCardThatExpiresWhileItsPaymentWaitsEndsThePaymentExpiredAndALateCardOnFileChangesNothing()
      throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    saga.runDueSteps(Instant.now());
    var card = cards.findForPayment(paymentIdOf(invoiceId)).orElseThrow();

    CardExpiryJob.runAt(context, card.expiresAt().plusSeconds(1));
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> "EXPIRED".equals(paymentState(acme, invoiceId)));
    sendCardOnFile(card.cardToken());
    awaitAuthorizationsTopicConsumed();
    // No Remittance Advice is expected: a delivery would fail the test.
    saga.runDueSteps(Instant.now());

    assertThat(paymentState(acme, invoiceId)).isEqualTo("EXPIRED");
    assertThat(availableCredit(acme)).isEqualTo(10_000_000);
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void anUnreadableMessageOrAnUnknownCardIsSkippedAndTheNextCardOnFileStillArrives(
      CapturedOutput output) throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    supplierAnswers(withSuccess());
    saga.runDueSteps(Instant.now());
    var cardToken = cardTokenOf(invoiceId);

    // Same key, so the same partition: a stuck message would hold back the good one behind it.
    send(cardToken, "CardOnFile", "{not json");
    send(cardToken, "CardOnFile", "{}");
    send(cardToken, "CardOnFile", null);
    send(cardToken, null, "{\"cardToken\": \"%s\"}".formatted(cardToken));
    send(cardToken, "CardOnFile", "{\"cardToken\": \"card-token-nobody-issued\"}");
    send(cardToken, "AuthorizationApproved", "{\"requestId\": \"request-1\"}");
    sendCardOnFile(cardToken);

    awaitCompleted(acme, invoiceId);
    assertThat(output)
        .contains("Skipped a CardOnFile message with no value")
        .contains("Skipped a message with no event-type header")
        .contains("Skipped CardOnFile for card card-token-nobody-issued");
  }

  // Grafana reads them to count, and alert on, Payments waiting for their card to be on file.
  @Test
  void theMetricsCountPaymentsWaitingForTheirCardToBeOnFileAndHowLongTheOldestHasWaited()
      throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    supplierAnswers(withSuccess());
    var issuedAt = Instant.now();
    waitingMetric.refreshAt(issuedAt);
    var waitingBefore = paymentsWaiting();

    saga.runDueSteps(issuedAt);
    waitingMetric.refreshAt(issuedAt.plus(Duration.ofMinutes(10)));
    var waitingAfterIssue = paymentsWaiting();
    var oldestWaitAfterIssue = oldestWaitSeconds();
    saga.cardOnFile(cardTokenOf(invoiceId), issuedAt);
    waitingMetric.refreshAt(issuedAt.plus(Duration.ofMinutes(10)));
    var waitingOnceOnFile = paymentsWaiting();
    saga.runDueSteps(issuedAt);

    assertThat(waitingAfterIssue).isEqualTo(waitingBefore + 1);
    // Another test's Payment may have waited longer.
    assertThat(oldestWaitAfterIssue).isGreaterThanOrEqualTo(600);
    assertThat(waitingOnceOnFile).isEqualTo(waitingBefore);
    assertThat(paymentState(acme, invoiceId)).isEqualTo("COMPLETED");
  }

  @Test
  void theCardIsLockedToTheInvoicesSupplierAndAmountAndIssuingItAgainReturnsTheSameCard()
      throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    supplierAnswers(withSuccess());
    runDueStepsWithCardsOnFile(acme, Instant.now());
    var body = invoice(acme, invoiceId).getResponse().getContentAsString();
    var paymentId = paymentIdOf(invoiceId);
    var supplierId = UUID.fromString(JsonPath.read(body, "$.supplierId"));
    var amount = new Money(1_240_000, Currency.getInstance("USD"));

    var card = cards.findForPayment(paymentId).orElseThrow();
    var again =
        cards.issueSingleUse(
            paymentId,
            new BuyerId(acme),
            supplierId,
            null,
            amount,
            Instant.now().plusSeconds(3_600));

    assertThat(card.cardToken()).isEqualTo(JsonPath.read(body, "$.payment.card.cardToken"));
    assertThat(card.buyerId()).isEqualTo(new BuyerId(acme));
    assertThat(card.supplierId()).isEqualTo(supplierId);
    assertThat(card.amount()).isEqualTo(amount);
    assertThat(card.status()).isEqualTo(SingleUseCard.Status.ACTIVE);
    assertThat(again).isEqualTo(card);
  }

  @Test
  void aCardNobodyChargedBeforeItsExpiryEndsItsPaymentExpiredAndReturnsTheCredit()
      throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    supplierAnswers(withSuccess());
    runDueStepsWithCardsOnFile(acme, Instant.now());
    var creditWhilePaid = availableCredit(acme);
    var card = cards.findForPayment(paymentIdOf(invoiceId)).orElseThrow();

    CardExpiryJob.runAt(context, card.expiresAt().plusSeconds(1));

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> "EXPIRED".equals(paymentState(acme, invoiceId)));
    assertThat(creditWhilePaid).isEqualTo(8_760_000);
    assertThat(availableCredit(acme)).isEqualTo(10_000_000);
  }

  // The saga passes the Acceptor ID into issuance, because cards can't call suppliers (ADR-0016).
  @Test
  @ExtendWith(PublishedEventsExtension.class)
  void theIssuedCardEventCarriesTheSuppliersAcceptorId(PublishedEvents events) throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000, "ACCEPTOR-1");
    supplierAnswers(withSuccess());

    runDueStepsWithCardsOnFile(acme, Instant.now());

    var card = cards.findForPayment(paymentIdOf(invoiceId)).orElseThrow();
    var issued =
        StreamSupport.stream(events.ofType(CardIssued.class).spliterator(), false)
            .filter(event -> event.cardToken().equals(card.cardToken()))
            .findFirst();
    assertThat(issued).get().extracting(CardIssued::acceptorId).isEqualTo("ACCEPTOR-1");
  }

  @Test
  void anExpiryRunTwiceAndDeliveredTwiceReleasesTheHoldOnceAndLeavesThePaymentExpired()
      throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    supplierAnswers(withSuccess());
    runDueStepsWithCardsOnFile(acme, Instant.now());
    // Another of the Buyer's Holds, which no release of this card's Hold may touch.
    holds.place(
        UUID.randomUUID(), new BuyerId(acme), new Money(1_000_000, Currency.getInstance("USD")));
    var card = cards.findForPayment(paymentIdOf(invoiceId)).orElseThrow();

    CardExpiryJob.runAt(context, card.expiresAt());
    CardExpiryJob.runAt(context, card.expiresAt());
    await().atMost(Duration.ofSeconds(10)).until(() -> completedExpiriesOf(card.cardToken()) == 1);
    // The same event again, as the registry redelivers one whose listener's completion was lost.
    transactions.executeWithoutResult(
        status ->
            events.publishEvent(
                new CardExpired(
                    card.cardToken(), card.paymentId(), card.buyerId(), card.amount())));
    await().atMost(Duration.ofSeconds(10)).until(() -> completedExpiriesOf(card.cardToken()) == 2);

    assertThat(paymentState(acme, invoiceId)).isEqualTo("EXPIRED");
    assertThat(availableCredit(acme)).isEqualTo(9_000_000);
    assertThat(cards.find(card.cardToken()).orElseThrow().status())
        .isEqualTo(SingleUseCard.Status.EXPIRED);
  }

  @Test
  void anAuthorizedCardWithNoClearingSevenDaysLaterEndsItsPaymentExpiredAndReturnsTheCredit()
      throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    supplierAnswers(withSuccess());
    runDueStepsWithCardsOnFile(acme, Instant.now());
    var card = cards.findForPayment(paymentIdOf(invoiceId)).orElseThrow();
    var authorizedAt = Instant.now();
    cards.authorize(card.cardToken(), authorizedAt);
    var creditWhileAuthorized = availableCredit(acme);

    CardExpiryJob.runAt(context, authorizedAt.plus(Duration.ofDays(7)));

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> "EXPIRED".equals(paymentState(acme, invoiceId)));
    assertThat(creditWhileAuthorized).isEqualTo(8_760_000);
    assertThat(availableCredit(acme)).isEqualTo(10_000_000);
  }

  @Test
  @ExtendWith(PublishedEventsExtension.class)
  void theSevenDayRuleRunTwiceExpiresTheCardOnceAndReleasesItsHoldOnce(PublishedEvents events)
      throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    supplierAnswers(withSuccess());
    runDueStepsWithCardsOnFile(acme, Instant.now());
    // Another of the Buyer's Holds, which no release of this card's Hold may touch.
    holds.place(
        UUID.randomUUID(), new BuyerId(acme), new Money(1_000_000, Currency.getInstance("USD")));
    var card = cards.findForPayment(paymentIdOf(invoiceId)).orElseThrow();
    var authorizedAt = Instant.now();
    cards.authorize(card.cardToken(), authorizedAt);

    CardExpiryJob.runAt(context, authorizedAt.plus(Duration.ofDays(7)));
    CardExpiryJob.runAt(context, authorizedAt.plus(Duration.ofDays(7)));
    await().atMost(Duration.ofSeconds(10)).until(() -> completedExpiriesOf(card.cardToken()) == 1);

    assertThat(
            events
                .ofType(CardExpired.class)
                .matching(expired -> expired.cardToken().equals(card.cardToken())))
        .hasSize(1);
    assertThat(paymentState(acme, invoiceId)).isEqualTo("EXPIRED");
    assertThat(availableCredit(acme)).isEqualTo(9_000_000);
  }

  @Test
  void notEnoughAvailableCreditFailsThePaymentAndLeavesTheInvoiceApproved() throws Exception {
    var acme = newBuyerWithCreditLine(1_000_000);
    var invoiceId = approvedInvoice(acme, 1_000_001);

    runDueStepsWithCardsOnFile(acme, Instant.now());

    assertThat(invoice(acme, invoiceId))
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"status": "APPROVED",
             "payment": {"state": "FAILED",
                         "failureReason": "Not enough Available Credit to place the Hold."}}
            """);
    assertThat(availableCredit(acme)).isEqualTo(1_000_000);
  }

  @Test
  void aBuyerWithoutACreditLineHasNoCreditToPayWith() throws Exception {
    var acme = newBuyer();
    var invoiceId = approvedInvoice(acme, 1);

    runDueStepsWithCardsOnFile(acme, Instant.now());

    assertThat(invoice(acme, invoiceId))
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"status": "APPROVED",
             "payment": {"state": "FAILED",
                         "failureReason": "Not enough Available Credit to place the Hold."}}
            """);
  }

  @Test
  void aCreditLineLoweredBelowOpenHoldsLeavesNegativeAvailableCreditThatBlocksNewHolds()
      throws Exception {
    var acme = newBuyerWithCreditLine(1_000_000);
    approvedInvoice(acme, 800_000);
    supplierAnswers(withSuccess());
    runDueStepsWithCardsOnFile(acme, Instant.now());

    setCreditLine(acme, 500_000, 200);
    var invoiceId = approvedInvoice(acme, 1);
    runDueStepsWithCardsOnFile(acme, Instant.now());

    assertThat(availableCredit(acme)).isEqualTo(-300_000);
    assertThat(paymentState(acme, invoiceId)).isEqualTo("FAILED");
  }

  @Test
  void aRedeliveredInvoiceApprovedStartsNoSecondPayment() throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = UUID.fromString(approvedInvoice(acme, 1_240_000));
    String supplierId =
        JsonPath.read(
            invoice(acme, invoiceId.toString()).getResponse().getContentAsString(), "$.supplierId");
    var event =
        new InvoiceApproved(
            invoiceId,
            new BuyerId(acme),
            UUID.fromString(supplierId),
            new Money(1_240_000, Currency.getInstance("USD")));

    // The same event again, as the registry redelivers one whose listener didn't complete.
    transactions.executeWithoutResult(status -> events.publishEvent(event));
    await().atMost(Duration.ofSeconds(10)).until(() -> completedDeliveriesOf(invoiceId) == 2);
    supplierAnswers(withSuccess());
    runDueStepsWithCardsOnFile(acme, Instant.now());

    assertThat(paymentState(acme, invoiceId.toString())).isEqualTo("COMPLETED");
    assertThat(availableCredit(acme)).isEqualTo(8_760_000);
  }

  @Test
  void twoPaymentsRacingForTheLastOfTheCreditPlaceExactlyOneHold() throws Exception {
    var acme = newBuyerWithCreditLine(1_000_000);
    var first = approvedInvoice(acme, 1_000_000);
    var second = approvedInvoice(acme, 1_000_000);
    // Each worker waits in the ledger until the other arrives, so their transactions overlap.
    var bothArrived = new CyclicBarrier(2);
    doAnswer(
            invocation -> {
              bothArrived.await(10, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .when(holds)
        .place(any(), eq(new BuyerId(acme)), any());
    supplierAnswers(withSuccess());
    var now = Instant.now();

    try (var workers = Executors.newFixedThreadPool(2)) {
      var passes =
          List.of(
              workers.submit(() -> saga.runDueSteps(now)),
              workers.submit(() -> saga.runDueSteps(now)));
      for (var pass : passes) {
        pass.get(30, TimeUnit.SECONDS);
      }
    }
    runDueStepsWithCardsOnFile(acme, now);

    assertThat(List.of(paymentState(acme, first), paymentState(acme, second)))
        .containsExactlyInAnyOrder("COMPLETED", "FAILED");
    assertThat(availableCredit(acme)).isZero();
  }

  @Test
  void severalWorkersDrainEveryDuePaymentAndRunEachOfItsStepsOnce() throws Exception {
    // More than ten, so a pass that stopped after ten steps would leave some due.
    var count = 40;
    var buyer = newBuyerWithCreditLine(100_000_000);
    var invoiceIds = new ArrayList<String>();
    for (var i = 0; i < count; i++) {
      invoiceIds.add(approvedInvoice(buyer, 1_000));
    }
    var deliveries =
        supplierAnswers(Collections.nCopies(count, withSuccess()).toArray(ResponseCreator[]::new));
    var now = Instant.now();

    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var passes =
          IntStream.range(0, 4).mapToObj(i -> workers.submit(() -> saga.runDueSteps(now))).toList();
      for (var pass : passes) {
        pass.get(60, TimeUnit.SECONDS);
      }
    }
    runDueStepsWithCardsOnFile(buyer, now);

    for (var invoiceId : invoiceIds) {
      assertThat(paymentState(buyer, invoiceId)).isEqualTo("COMPLETED");
    }
    var buyerId = new BuyerId(buyer);
    verify(holds, times(count)).place(any(), eq(buyerId), any());
    verify(cards, times(count)).issueSingleUse(any(), eq(buyerId), any(), any(), any(), any());
    assertThat(deliveries).hasSize(count);
    assertThat(availableCredit(buyer)).isEqualTo(100_000_000 - count * 1_000L);
  }

  @Test
  void aStepWhoseConnectionIsLostIsRetriedAfterItsBackoff() throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    // The first attempt places the Hold, then PostgreSQL drops its connection before the commit.
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              return jdbc.sql("select pg_terminate_backend(pg_backend_pid())")
                  .query()
                  .singleValue();
            })
        .doCallRealMethod()
        .when(holds)
        .place(any(), eq(new BuyerId(acme)), any());
    supplierAnswers(withSuccess());
    var failedAt = Instant.now();

    runDueStepsWithCardsOnFile(acme, failedAt);
    var stateAfterFailure = paymentState(acme, invoiceId);
    var creditAfterFailure = availableCredit(acme);
    runDueStepsWithCardsOnFile(acme, failedAt.plusSeconds(9));
    var stateBeforeBackoffEnds = paymentState(acme, invoiceId);
    runDueStepsWithCardsOnFile(acme, failedAt.plusSeconds(10));

    assertThat(stateAfterFailure).isEqualTo("STARTED");
    assertThat(creditAfterFailure).isEqualTo(10_000_000);
    assertThat(stateBeforeBackoffEnds).isEqualTo("STARTED");
    assertThat(paymentState(acme, invoiceId)).isEqualTo("COMPLETED");
    assertThat(availableCredit(acme)).isEqualTo(8_760_000);
  }

  @Test
  void aStepThatKeepsFailingTechnicallyFailsThePaymentForGoodOnItsFifthAttempt() throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    doThrow(new TransientDataAccessResourceException("Connection lost"))
        .when(holds)
        .place(any(), eq(new BuyerId(acme)), any());
    // Each pass comes after the longest backoff, so every pass retries.
    var now = Instant.now();

    for (var attempt = 1; attempt <= 4; attempt++) {
      runDueStepsWithCardsOnFile(acme, now);
      now = now.plus(Duration.ofMinutes(5));
    }
    var stateAfterFourAttempts = paymentState(acme, invoiceId);
    runDueStepsWithCardsOnFile(acme, now);

    assertThat(stateAfterFourAttempts).isEqualTo("STARTED");
    assertThat(invoice(acme, invoiceId))
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"status": "APPROVED",
             "payment": {"state": "FAILED",
                         "failureReason": "Placing the Hold failed 5 times for technical reasons."}}
            """);
    assertThat(availableCredit(acme)).isEqualTo(10_000_000);
  }

  @Test
  void aCardThatKeepsFailingToIssueReleasesTheHoldAndFailsThePaymentOnTheFifthAttempt()
      throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    doThrow(new TransientDataAccessResourceException("Connection lost"))
        .when(cards)
        .issueSingleUse(any(), eq(new BuyerId(acme)), any(), any(), any(), any());
    // The first pass places the Hold, then fails to issue the card; each later pass comes after the
    // longest backoff, so every pass retries.
    var now = Instant.now();

    for (var attempt = 1; attempt <= 4; attempt++) {
      runDueStepsWithCardsOnFile(acme, now);
      now = now.plus(Duration.ofMinutes(5));
    }
    var stateAfterFourAttempts = paymentState(acme, invoiceId);
    var creditAfterFourAttempts = availableCredit(acme);
    runDueStepsWithCardsOnFile(acme, now);

    assertThat(stateAfterFourAttempts).isEqualTo("HOLD_PLACED");
    assertThat(creditAfterFourAttempts).isEqualTo(8_760_000);
    assertThat(invoice(acme, invoiceId))
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"status": "APPROVED",
             "payment": {"state": "FAILED",
                         "failureReason": "Issuing the Single-Use Card failed 5 times for technical reasons."}}
            """);
    assertThat(availableCredit(acme)).isEqualTo(10_000_000);
  }

  @Test
  void aRetriedDeliverySendsTheSameLinkAgainAndCompletesThePaymentOnce() throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    // The Supplier fails the first delivery, accepts the second, and would fail the test on a
    // third.
    var deliveries = supplierAnswers(withServerError(), withSuccess());
    var failedAt = Instant.now();

    runDueStepsWithCardsOnFile(acme, failedAt);
    var stateAfterFailure = paymentState(acme, invoiceId);
    runDueStepsWithCardsOnFile(acme, failedAt.plusSeconds(10));
    runDueStepsWithCardsOnFile(acme, failedAt.plus(Duration.ofMinutes(5)));

    assertThat(stateAfterFailure).isEqualTo("CARD_ON_FILE");
    // The same Idempotency-Key, link and expiry: the Supplier can keep either and drop the other.
    assertThat(deliveries).hasSize(2);
    assertThat(deliveries.get(1)).isEqualTo(deliveries.getFirst());
    assertThat(paymentState(acme, invoiceId)).isEqualTo("COMPLETED");
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void aRedirectOrAnErrorFromTheSupplierFailsTheAttemptAndItsAnswerStaysOutOfTheLogs(
      CapturedOutput output) throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    // A redirect elsewhere, then an error answer that echoes the advice back, link included.
    var deliveries =
        supplierAnswers(
            withStatus(HttpStatus.SEE_OTHER).location(URI.create("https://elsewhere.example/")),
            request ->
                withBadRequest()
                    .body(((MockClientHttpRequest) request).getBodyAsString())
                    .createResponse(request));
    var now = Instant.now();

    runDueStepsWithCardsOnFile(acme, now);
    var stateAfterRedirect = paymentState(acme, invoiceId);
    runDueStepsWithCardsOnFile(acme, now.plusSeconds(10));

    assertThat(stateAfterRedirect).isEqualTo("CARD_ON_FILE");
    assertThat(paymentState(acme, invoiceId)).isEqualTo("CARD_ON_FILE");
    assertThat(deliveries).hasSize(2);
    String link = JsonPath.read(deliveries.getFirst().body(), "$.cardLink");
    assertThat(output)
        .contains("answered 303")
        .contains("answered 400")
        .doesNotContain(link.substring(link.lastIndexOf('/') + 1));
  }

  @Test
  void anAdviceTheSupplierNeverAcceptsCancelsTheCardReleasesTheHoldAndFailsThePayment()
      throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    var deliveries =
        supplierAnswers(
            withServerError(),
            withServerError(),
            withServerError(),
            withServerError(),
            withServerError());
    // The first pass places the Hold, issues the card, then fails to deliver the advice; each later
    // pass comes after the longest backoff, so every pass retries.
    var now = Instant.now();

    for (var attempt = 1; attempt <= 4; attempt++) {
      runDueStepsWithCardsOnFile(acme, now);
      now = now.plus(Duration.ofMinutes(5));
    }
    var stateAfterFourAttempts = paymentState(acme, invoiceId);
    runDueStepsWithCardsOnFile(acme, now);

    assertThat(stateAfterFourAttempts).isEqualTo("CARD_ON_FILE");
    assertThat(invoice(acme, invoiceId))
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"status": "APPROVED",
             "payment": {"state": "FAILED",
                         "failureReason": "Delivering the Remittance Advice failed 5 times for technical reasons."}}
            """);
    var paymentId = paymentIdOf(invoiceId);
    assertThat(cards.findForPayment(paymentId).orElseThrow().status())
        .isEqualTo(SingleUseCard.Status.CANCELLED);
    assertThat(availableCredit(acme)).isEqualTo(10_000_000);
    // The released Hold keeps its record.
    assertThat(
            jdbc.sql("select status from ledger.hold where id = :id")
                .param("id", paymentId)
                .query(String.class)
                .single())
        .isEqualTo("RELEASED");
    // The Supplier may have received the link before an answer was lost: it shows nothing now.
    String link = JsonPath.read(deliveries.getLast().body(), "$.cardLink");
    assertThat(mvc.get().uri(link).exchange()).hasStatus(410);
  }

  /**
   * The platform stops during the step that follows each state, after the step's side effect but
   * before its transaction commits, then starts again. The stop rolls back what the step wrote,
   * except the Remittance Advice's record, which commits on its own before the POST (ADR-0014), and
   * the advice the Supplier already received.
   */
  @ParameterizedTest(name = "stopped at {0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          # State  | left after the stop: Holds | cards | advice records | advice the Supplier has
          STARTED      | 0 | 0 | 0 | 0
          HOLD_PLACED  | 1 | 0 | 0 | 0
          CARD_ON_FILE | 1 | 1 | 1 | 1
          """)
  void aPlatformStoppedMidStepAndStartedAgainCompletesThePaymentWithOneHoldAndOneCard(
      Payment.State stoppedAt, int holdsLeft, int cardsLeft, int advicesLeft, int deliveriesLeft)
      throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    stopOnceDuringTheStepAfter(stoppedAt, acme, UUID.fromString(invoiceId));
    var deliveries = supplierAnswers(withSuccess(), withSuccess());

    assertThatThrownBy(() -> runDueStepsWithCardsOnFile(acme, Instant.now()))
        .isInstanceOf(PlatformStopped.class);
    var stateAfterStop = paymentState(acme, invoiceId);
    var leftAfterStop =
        List.of(holdsOf(acme), cardsOf(acme), advicesFor(invoiceId), deliveries.size());
    // Started again: the worker's next pass.
    runDueStepsWithCardsOnFile(acme, Instant.now());

    assertThat(stateAfterStop).isEqualTo(stoppedAt.name());
    assertThat(leftAfterStop).containsExactly(holdsLeft, cardsLeft, advicesLeft, deliveriesLeft);
    assertThat(paymentState(acme, invoiceId)).isEqualTo("COMPLETED");
    assertThat(List.of(holdsOf(acme), cardsOf(acme), advicesFor(invoiceId)))
        .containsExactly(1, 1, 1);
    assertThat(availableCredit(acme)).isEqualTo(8_760_000);
    // A Supplier that received the advice before the stop receives the same one again, to drop.
    assertThat(deliveries).hasSize(deliveriesLeft + 1).allMatch(deliveries.getFirst()::equals);
    // No PAN left in the vault for a card that was rolled back.
    assertThat(
            jdbc.sql(
                    """
                    select count(*) from vault.card_secret
                     where card_token not in (select card_token from cards.card)
                    """)
                .query(Long.class)
                .single())
        .isZero();
  }

  // The step's real call runs, then the platform stops before the step's transaction commits. The
  // step runs normally after that, as it does once the platform has started again.
  private void stopOnceDuringTheStepAfter(Payment.State state, UUID buyerId, UUID invoiceId) {
    var stopAfterTheRealCall =
        doAnswer(
                invocation -> {
                  invocation.callRealMethod();
                  throw new PlatformStopped();
                })
            .doCallRealMethod();
    var buyer = new BuyerId(buyerId);
    switch (state) {
      case STARTED -> stopAfterTheRealCall.when(holds).place(any(), eq(buyer), any());
      case HOLD_PLACED ->
          stopAfterTheRealCall
              .when(cards)
              .issueSingleUse(any(), eq(buyer), any(), any(), any(), any());
      case CARD_ON_FILE ->
          stopAfterTheRealCall
              .when(remittanceAdvices)
              .deliver(argThat(advice -> advice.invoiceId().equals(invoiceId)), any());
      case CARD_ISSUED, COMPLETED, FAILED, EXPIRED ->
          throw new IllegalArgumentException("No step follows " + state);
    }
  }

  // A worker's pass, with authorization-service's "card on file" arriving for each of the Buyer's
  // cards as soon as it is issued, as it does when the service and Kafka are up; the tests of the
  // wait send it over
  // Kafka instead.
  private void runDueStepsWithCardsOnFile(UUID buyerId, Instant now) {
    saga.runDueSteps(now);
    // One module's schema at a time (ADR-0009): the Buyer's waiting Payments, then their cards.
    jdbc.sql("select id from payables.payment where state = 'CARD_ISSUED' and buyer_id = :buyerId")
        .param("buyerId", buyerId)
        .query(UUID.class)
        .list()
        .forEach(
            paymentId ->
                saga.cardOnFile(cards.findForPayment(paymentId).orElseThrow().cardToken(), now));
    saga.runDueSteps(now);
  }

  // authorization-service's CardOnFile as its outbox relay sends it: keyed by the Card Token, its
  // event type in a header.
  private void sendCardOnFile(String cardToken) throws Exception {
    send(cardToken, "CardOnFile", "{\"cardToken\": \"%s\"}".formatted(cardToken));
  }

  private void send(String key, String eventType, String json) throws Exception {
    var record = new ProducerRecord<>("authorizations", key, json);
    if (eventType != null) {
      record.headers().add("event-type", eventType.getBytes(UTF_8));
    }
    try (var producer =
        new KafkaProducer<String, String>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class))) {
      producer.send(record).get(10, TimeUnit.SECONDS);
    }
  }

  // Waits until the platform's consumer group has committed every message on the topic, so a test
  // can check that one changed nothing.
  private void awaitAuthorizationsTopicConsumed() {
    try (var admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(
              () -> {
                var partitions =
                    admin
                        .describeTopics(List.of("authorizations"))
                        .allTopicNames()
                        .get()
                        .get("authorizations")
                        .partitions()
                        .stream()
                        .map(info -> new TopicPartition("authorizations", info.partition()))
                        .toList();
                var ends =
                    admin
                        .listOffsets(
                            partitions.stream()
                                .collect(
                                    Collectors.toMap(
                                        partition -> partition, partition -> OffsetSpec.latest())))
                        .all()
                        .get();
                var committed =
                    admin
                        .listConsumerGroupOffsets(CardOnFileListener.GROUP_ID)
                        .partitionsToOffsetAndMetadata()
                        .get();
                for (var partition : partitions) {
                  var end = ends.get(partition).offset();
                  var read = committed.get(partition);
                  assertThat(read == null ? 0 : read.offset()).as("%s", partition).isEqualTo(end);
                }
              });
    }
  }

  private double paymentsWaiting() {
    return meters.get("payments.waiting.card.on.file").gauge().value();
  }

  private double oldestWaitSeconds() {
    return meters.get("payments.waiting.card.on.file.oldest").gauge().value();
  }

  private String cardTokenOf(String invoiceId) {
    return cards.findForPayment(paymentIdOf(invoiceId)).orElseThrow().cardToken();
  }

  // Runs the worker until the Payment completes, as the card on file arrives over Kafka.
  private void awaitCompleted(UUID buyerId, String invoiceId) {
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () -> {
              saga.runDueSteps(Instant.now());
              return "COMPLETED".equals(paymentState(buyerId, invoiceId));
            });
  }

  private int holdsOf(UUID buyerId) {
    return jdbc.sql("select count(*) from ledger.hold where buyer_id = :buyerId")
        .param("buyerId", buyerId)
        .query(Integer.class)
        .single();
  }

  private int cardsOf(UUID buyerId) {
    return jdbc.sql("select count(*) from cards.card where buyer_id = :buyerId")
        .param("buyerId", buyerId)
        .query(Integer.class)
        .single();
  }

  private int advicesFor(String invoiceId) {
    return jdbc.sql(
            "select count(*) from suppliers.remittance_advice where invoice_id = :invoiceId")
        .param("invoiceId", UUID.fromString(invoiceId))
        .query(Integer.class)
        .single();
  }

  /** A Remittance Advice as the Supplier's address received it. */
  record Delivery(String idempotencyKey, String body) {}

  // The Supplier's remittance address answers one advice with each response, in turn; the list
  // records every advice it receives. An advice beyond the responses fails the test.
  private List<Delivery> supplierAnswers(ResponseCreator... responses) {
    var deliveries = new CopyOnWriteArrayList<Delivery>();
    for (var response : responses) {
      supplier
          .expect(requestTo(GLOBEX))
          .andExpect(method(HttpMethod.POST))
          .andExpect(
              request ->
                  deliveries.add(
                      new Delivery(
                          request.getHeaders().getFirst("Idempotency-Key"),
                          ((MockClientHttpRequest) request).getBodyAsString())))
          .andRespond(response);
    }
    return deliveries;
  }

  /**
   * Stands in for the JVM stopping mid-step: an Error, which the worker doesn't handle, so nothing
   * after it runs and the step's transaction rolls back, as PostgreSQL does when a connection dies.
   */
  static class PlatformStopped extends Error {}

  private UUID newBuyerWithCreditLine(long creditLine) {
    var buyerId = newBuyer();
    setCreditLine(buyerId, creditLine, 201);
    return buyerId;
  }

  private UUID newBuyer() {
    var buyerId = UUID.randomUUID();
    var buyer =
        mvc.put()
            .uri("/v1/buyers/{buyerId}", buyerId)
            .with(issuerStaff())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name": "Buyer %s", "approvalThreshold": {"minorUnits": %d, "currency": "USD"}}
                """
                    .formatted(buyerId, APPROVAL_THRESHOLD))
            .exchange();
    assertThat(buyer).hasStatus(201);
    return buyerId;
  }

  private void setCreditLine(UUID buyerId, long creditLine, int expectedStatus) {
    var response =
        mvc.put()
            .uri("/v1/buyers/{buyerId}/credit-line", buyerId)
            .with(issuerStaff())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"minorUnits": %d, "currency": "USD"}
                """
                    .formatted(creditLine))
            .exchange();
    assertThat(response).hasStatus(expectedStatus);
  }

  // The test context's registry outlives each test, so a test compares counts before and after.
  private double paymentsReached(String state) {
    var counter = meters.find("payments.state.changes").tag("state", state).counter();
    return counter == null ? 0 : counter.count();
  }

  private long stepsTimed(String step) {
    var timer = meters.find("payments.steps").tag("step", step).timer();
    return timer == null ? 0 : timer.count();
  }

  // Submitted, approved, and its Payment started by InvoiceApproved, which arrives asynchronously.
  private String approvedInvoice(UUID buyerId, long minorUnits)
      throws UnsupportedEncodingException {
    return approvedInvoice(buyerId, minorUnits, null);
  }

  private String approvedInvoice(UUID buyerId, long minorUnits, String acceptorId)
      throws UnsupportedEncodingException {
    var submitted =
        mvc.post()
            .uri("/v1/invoices")
            .with(tokenOf(buyerId, "ROLE_erp", "acme-erp"))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "supplier": {
                    "name": "Globex",
                    "taxId": "TAX-%s",
                    %s
                    "buyerReference": "GLOBEX-01",
                    "remittanceAddress": "https://globex.example/remittance-advice"
                  },
                  "invoiceNumber": "INV-1",
                  "amount": {"minorUnits": %d, "currency": "USD"},
                  "dueDate": "2026-11-30"
                }
                """
                    .formatted(
                        UUID.randomUUID(),
                        acceptorId == null ? "" : "\"acceptorId\": \"%s\",".formatted(acceptorId),
                        minorUnits))
            .exchange();
    assertThat(submitted).hasStatus(201);
    String invoiceId = JsonPath.read(submitted.getResponse().getContentAsString(), "$.id");
    var approved =
        mvc.post()
            .uri("/v1/invoices/{invoiceId}/approval", invoiceId)
            .with(tokenOf(buyerId, "ROLE_approver", "ann"))
            .exchange();
    assertThat(approved).hasStatus(200);
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> "STARTED".equals(paymentState(buyerId, invoiceId)));
    return invoiceId;
  }

  private UUID paymentIdOf(String invoiceId) {
    return jdbc.sql("select id from payables.payment where invoice_id = :invoiceId")
        .param("invoiceId", UUID.fromString(invoiceId))
        .query(UUID.class)
        .single();
  }

  private long completedDeliveriesOf(UUID invoiceId) {
    return completedEvents.findAll().stream()
        .map(EventPublication::getEvent)
        .filter(
            event ->
                event instanceof InvoiceApproved approved && approved.invoiceId().equals(invoiceId))
        .count();
  }

  private long completedExpiriesOf(String cardToken) {
    return completedEvents.findAll().stream()
        .map(EventPublication::getEvent)
        .filter(
            event -> event instanceof CardExpired expired && expired.cardToken().equals(cardToken))
        .count();
  }

  private MvcTestResult invoice(UUID buyerId, String invoiceId) {
    var response =
        mvc.get()
            .uri("/v1/invoices/{invoiceId}", invoiceId)
            .with(tokenOf(buyerId, "ROLE_erp", "acme-erp"))
            .exchange();
    assertThat(response).hasStatus(200);
    return response;
  }

  // Null while the Invoice shows no Payment.
  private String paymentState(UUID buyerId, String invoiceId) throws UnsupportedEncodingException {
    var body = invoice(buyerId, invoiceId).getResponse().getContentAsString();
    return JsonPath.using(
            Configuration.defaultConfiguration().addOptions(Option.SUPPRESS_EXCEPTIONS))
        .parse(body)
        .read("$.payment.state");
  }

  private long availableCredit(UUID buyerId) throws UnsupportedEncodingException {
    var response =
        mvc.get().uri("/v1/buyers/{buyerId}/credit", buyerId).with(issuerStaff()).exchange();
    assertThat(response).hasStatus(200);
    return JsonPath.<Number>read(
            response.getResponse().getContentAsString(), "$.availableCredit.minorUnits")
        .longValue();
  }

  private static JwtRequestPostProcessor issuerStaff() {
    return jwt().authorities(new SimpleGrantedAuthority("ROLE_issuer-admin"));
  }

  private static JwtRequestPostProcessor tokenOf(UUID buyerId, String authority, String name) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject("user-" + name)
                    .claim("preferred_username", name)
                    .claim("buyer_id", buyerId.toString()))
        .authorities(new SimpleGrantedAuthority(authority));
  }
}
