package dev.rgonz.payments.platform.payables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.cards.Cards;
import dev.rgonz.payments.platform.cards.SingleUseCard;
import dev.rgonz.payments.platform.ledger.Holds;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.EventPublication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An approved Invoice's Payment runs through the saga: over HTTP with mocked tokens, with the
 * worker driven by hand instead of on its schedule.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "payables.payment-worker.enabled=false")
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
  @MockitoSpyBean Holds holds;
  @MockitoSpyBean Cards cards;

  @Test
  void anApprovedInvoicesPaymentPlacesAHoldThenIssuesASingleUseCardShownWithoutItsPan()
      throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    supplierAnswers(withSuccess());
    var now = Instant.now();

    saga.runDueSteps(now);

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

    saga.runDueSteps(now);

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

  @Test
  void theCardIsLockedToTheInvoicesSupplierAndAmountAndIssuingItAgainReturnsTheSameCard()
      throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    supplierAnswers(withSuccess());
    saga.runDueSteps(Instant.now());
    var body = invoice(acme, invoiceId).getResponse().getContentAsString();
    var paymentId = paymentIdOf(invoiceId);
    var supplierId = UUID.fromString(JsonPath.read(body, "$.supplierId"));
    var amount = new Money(1_240_000, Currency.getInstance("USD"));

    var card = cards.findForPayment(paymentId).orElseThrow();
    var again =
        cards.issueSingleUse(
            paymentId, new BuyerId(acme), supplierId, amount, Instant.now().plusSeconds(3_600));

    assertThat(card.cardToken()).isEqualTo(JsonPath.read(body, "$.payment.card.cardToken"));
    assertThat(card.buyerId()).isEqualTo(new BuyerId(acme));
    assertThat(card.supplierId()).isEqualTo(supplierId);
    assertThat(card.amount()).isEqualTo(amount);
    assertThat(card.status()).isEqualTo(SingleUseCard.Status.ACTIVE);
    assertThat(again).isEqualTo(card);
  }

  @Test
  void noTableOutsideTheVaultHoldsAPan() throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    supplierAnswers(withSuccess());
    saga.runDueSteps(Instant.now());
    String cardToken =
        JsonPath.read(
            invoice(acme, invoiceId).getResponse().getContentAsString(),
            "$.payment.card.cardToken");

    // A simple check: #20 adds the exhaustive search of every table and log line.
    assertThat(tablesOutsideTheVaultMatching("990000[0-9]{10}")).isEmpty();
    // The search does find a value that is stored, such as the Card Token.
    assertThat(tablesOutsideTheVaultMatching(cardToken)).contains("cards.card");
    assertThat(
            jdbc.sql("select count(*) from vault.card_secret where card_token = :cardToken")
                .param("cardToken", cardToken)
                .query(Long.class)
                .single())
        .isOne();
  }

  @Test
  void notEnoughAvailableCreditFailsThePaymentAndLeavesTheInvoiceApproved() throws Exception {
    var acme = newBuyerWithCreditLine(1_000_000);
    var invoiceId = approvedInvoice(acme, 1_000_001);

    saga.runDueSteps(Instant.now());

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

    saga.runDueSteps(Instant.now());

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
    saga.runDueSteps(Instant.now());

    setCreditLine(acme, 500_000, 200);
    var invoiceId = approvedInvoice(acme, 1);
    saga.runDueSteps(Instant.now());

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
    saga.runDueSteps(Instant.now());

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

    assertThat(List.of(paymentState(acme, first), paymentState(acme, second)))
        .containsExactlyInAnyOrder("COMPLETED", "FAILED");
    assertThat(availableCredit(acme)).isZero();
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

    saga.runDueSteps(failedAt);
    var stateAfterFailure = paymentState(acme, invoiceId);
    var creditAfterFailure = availableCredit(acme);
    saga.runDueSteps(failedAt.plusSeconds(9));
    var stateBeforeBackoffEnds = paymentState(acme, invoiceId);
    saga.runDueSteps(failedAt.plusSeconds(10));

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
      saga.runDueSteps(now);
      now = now.plus(Duration.ofMinutes(5));
    }
    var stateAfterFourAttempts = paymentState(acme, invoiceId);
    saga.runDueSteps(now);

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
        .issueSingleUse(any(), eq(new BuyerId(acme)), any(), any(), any());
    // The first pass places the Hold, then fails to issue the card; each later pass comes after the
    // longest backoff, so every pass retries.
    var now = Instant.now();

    for (var attempt = 1; attempt <= 4; attempt++) {
      saga.runDueSteps(now);
      now = now.plus(Duration.ofMinutes(5));
    }
    var stateAfterFourAttempts = paymentState(acme, invoiceId);
    var creditAfterFourAttempts = availableCredit(acme);
    saga.runDueSteps(now);

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
      saga.runDueSteps(now);
      now = now.plus(Duration.ofMinutes(5));
    }
    var stateAfterFourAttempts = paymentState(acme, invoiceId);
    saga.runDueSteps(now);

    assertThat(stateAfterFourAttempts).isEqualTo("CARD_ISSUED");
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

  @Test
  void aPlatformStoppedMidStepLeavesNoHoldAndTheNextPassPlacesExactlyOne() throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    // The platform stops after the Hold is placed, before the Payment's new state is saved.
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new PlatformStopped();
            })
        .doCallRealMethod()
        .when(holds)
        .place(any(), eq(new BuyerId(acme)), any());
    supplierAnswers(withSuccess());

    assertThatThrownBy(() -> saga.runDueSteps(Instant.now())).isInstanceOf(PlatformStopped.class);
    var stateAfterStop = paymentState(acme, invoiceId);
    var creditAfterStop = availableCredit(acme);
    saga.runDueSteps(Instant.now());

    assertThat(stateAfterStop).isEqualTo("STARTED");
    assertThat(creditAfterStop).isEqualTo(10_000_000);
    assertThat(paymentState(acme, invoiceId)).isEqualTo("COMPLETED");
    assertThat(availableCredit(acme)).isEqualTo(8_760_000);
  }

  @Test
  void aPlatformStoppedWhileIssuingTheCardLeavesNoCardDataAndTheNextPassIssuesExactlyOne()
      throws Exception {
    var acme = newBuyerWithCreditLine(10_000_000);
    var invoiceId = approvedInvoice(acme, 1_240_000);
    // The platform stops after the card and its PAN are stored, before the Payment's new state.
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new PlatformStopped();
            })
        .doCallRealMethod()
        .when(cards)
        .issueSingleUse(any(), eq(new BuyerId(acme)), any(), any(), any());
    supplierAnswers(withSuccess());

    assertThatThrownBy(() -> saga.runDueSteps(Instant.now())).isInstanceOf(PlatformStopped.class);
    var stateAfterStop = paymentState(acme, invoiceId);
    var cardAfterStop = cards.findForPayment(paymentIdOf(invoiceId));
    saga.runDueSteps(Instant.now());

    assertThat(stateAfterStop).isEqualTo("HOLD_PLACED");
    assertThat(cardAfterStop).isEmpty();
    assertThat(paymentState(acme, invoiceId)).isEqualTo("COMPLETED");
    assertThat(cards.findForPayment(paymentIdOf(invoiceId))).isPresent();
    assertThat(availableCredit(acme)).isEqualTo(8_760_000);
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

  // Submitted, approved, and its Payment started by InvoiceApproved, which arrives asynchronously.
  private String approvedInvoice(UUID buyerId, long minorUnits)
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
                    "buyerReference": "GLOBEX-01",
                    "remittanceAddress": "https://globex.example/remittance-advice"
                  },
                  "invoiceNumber": "INV-1",
                  "amount": {"minorUnits": %d, "currency": "USD"},
                  "dueDate": "2026-11-30"
                }
                """
                    .formatted(UUID.randomUUID(), minorUnits))
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

  // Every table outside the vault's schema (and PostgreSQL's own) with a row whose text matches.
  private List<String> tablesOutsideTheVaultMatching(String regex) {
    var tables =
        jdbc.sql(
                """
                select format('%I.%I', table_schema, table_name)
                  from information_schema.tables
                 where table_type = 'BASE TABLE'
                   and table_schema not in ('vault', 'pg_catalog', 'information_schema')
                """)
            .query(String.class)
            .list();
    return tables.stream()
        .filter(
            table ->
                jdbc.sql("select exists (select 1 from " + table + " t where t::text ~ :regex)")
                    .param("regex", regex)
                    .query(Boolean.class)
                    .single())
        .toList();
  }

  private long completedDeliveriesOf(UUID invoiceId) {
    return completedEvents.findAll().stream()
        .map(EventPublication::getEvent)
        .filter(
            event ->
                event instanceof InvoiceApproved approved && approved.invoiceId().equals(invoiceId))
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
