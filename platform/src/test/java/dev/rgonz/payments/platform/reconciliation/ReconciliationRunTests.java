package dev.rgonz.payments.platform.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.cards.CardExpiryJob;
import dev.rgonz.payments.platform.cards.Cards;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issuer staff run a Reconciliation for a Business Date over HTTP, on PostgreSQL: every Clearing of
 * that day that didn't match, every Undercharge, every Authorization that never cleared, every
 * Payment that ended unpaid and every mismatched Settlement becomes one {@code OPEN} Break, and a
 * re-run finds them instead of adding more. Clearings, Settlements, Invoices and Payments are
 * written straight into their modules' tables, as those modules would have recorded them, so each
 * needs no Network to produce it; a never-cleared Charge expires through the real expiry job. Runs
 * for today share it with other tests, so those assert only the Breaks naming their own records.
 * The books' checks are {@link BooksOutOfBalanceTests}'.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ReconciliationRunTests {

  private static final String ISSUER_ADMIN = "ROLE_issuer-admin";
  private static final Currency USD = Currency.getInstance("USD");
  private static final long AUTHORIZED = 1_240_000;
  // Each test reconciles a Business Date of its own, long before any other test's Clearings.
  private static final AtomicInteger DAYS = new AtomicInteger();

  @Autowired MockMvcTester mvc;
  @Autowired JdbcClient jdbc;
  @Autowired Cards cards;
  @Autowired JsonMapper json;
  @Autowired ApplicationContext context;

  @Test
  void aRunRaisesOneOpenBreakPerUnmatchedClearingAndPerUndercharge() {
    var date = newBusinessDate();
    var fileId = clearingFile(date);
    var buyerId = new BuyerId(UUID.randomUUID());
    var paid = payment(buyerId, "COMPLETED", AUTHORIZED, null);
    var cardToken = card(buyerId, UUID.fromString(paid.paymentId()));
    var above =
        clearing(fileId, cardToken, 1_300_000, "USD", AUTHORIZED, "ABOVE_AUTHORIZED_AMOUNT");
    var noAuthorization = clearing(fileId, cardToken, 500_000, "USD", null, "NO_AUTHORIZATION");
    var unknownCard = clearing(fileId, null, 700_000, "USD", null, "UNKNOWN_CARD");
    var otherCurrency = clearing(fileId, cardToken, 800_000, "EUR", null, "OTHER_CURRENCY");
    var reversed =
        clearing(
            fileId, cardToken, AUTHORIZED, "USD", AUTHORIZED, "AGAINST_REVERSED_AUTHORIZATION");
    var expired = clearing(fileId, cardToken, AUTHORIZED, "USD", AUTHORIZED, "AFTER_HOLD_EXPIRED");
    var twice =
        clearing(fileId, cardToken, AUTHORIZED, "USD", AUTHORIZED, "AUTHORIZATION_ALREADY_CLEARED");
    var undercharge = clearing(fileId, cardToken, 1_000_000, "USD", AUTHORIZED, "MATCHED");
    clearing(fileId, cardToken, AUTHORIZED, "USD", AUTHORIZED, "MATCHED");
    // Another day's Clearing is that day's run's to find.
    clearing(clearingFile(date.plusDays(1)), null, 100, "USD", null, "UNKNOWN_CARD");

    var response = run(date, ISSUER_ADMIN);

    assertThat(response).hasStatus(201);
    assertThat(response)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"businessDate": "%s", "breaksFound": 8, "newBreaks": 8, "knownBreaks": 0}
            """
                .formatted(date));
    var buyer = buyerId.value();
    assertThat(breaksOn(date))
        .containsExactlyInAnyOrder(
            tuple("ABOVE_AUTHORIZED_AMOUNT", above, "OPEN", buyer, 60_000L, "USD"),
            tuple("NO_AUTHORIZATION", noAuthorization, "OPEN", buyer, 500_000L, "USD"),
            tuple("UNKNOWN_CARD", unknownCard, "OPEN", null, 700_000L, "USD"),
            tuple("OTHER_CURRENCY", otherCurrency, "OPEN", buyer, 800_000L, "EUR"),
            tuple("AGAINST_REVERSED_AUTHORIZATION", reversed, "OPEN", buyer, AUTHORIZED, "USD"),
            tuple("AFTER_HOLD_EXPIRED", expired, "OPEN", buyer, AUTHORIZED, "USD"),
            tuple("AUTHORIZATION_ALREADY_CLEARED", twice, "OPEN", buyer, AUTHORIZED, "USD"),
            tuple("UNDERCHARGE", undercharge, "OPEN", buyer, 240_000L, "USD"));
    // The Break keeps the Clearing's facts as they were when it was raised, and names the Invoice
    // the card's Payment pays.
    assertThat(facts(undercharge))
        .isEqualTo(
            json.readTree(
                """
            {"clearing": {"networkRecordId": "%s", "fileId": "%s", "businessDate": "%s",
                          "cardToken": "%s", "buyerId": "%s", "authorizationRequestId": "A%s",
                          "cleared": {"minorUnits": 1000000, "currency": "USD"},
                          "authorized": {"minorUnits": 1240000, "currency": "USD"},
                          "outcome": "MATCHED"},
             "invoice": {"id": "%s"}}
            """
                    .formatted(
                        undercharge,
                        fileId,
                        date,
                        cardToken,
                        buyer,
                        undercharge,
                        paid.invoiceId())));
  }

  // A card charged 8 days ago whose Charge never cleared expires by the 7-day rule today, and its
  // Payment ends EXPIRED through payables' listener, as in production. Another Payment failed
  // today, and one failed the day before: that day's run's to find.
  @Test
  void aPaymentThatEndedExpiredOrFailedRaisesAnUnpaidInvoiceBreakNamingItsInvoice() {
    var buyerId = new BuyerId(UUID.randomUUID());
    var expired = neverClearedCharge(buyerId);
    var failed = payment(buyerId, "FAILED", 300_000, today());
    var failedTheDayBefore = payment(buyerId, "FAILED", 300_000, today().minusDays(1));

    assertThat(run(today(), ISSUER_ADMIN)).hasStatus(201);

    var buyer = buyerId.value();
    assertThat(
            breaksNaming(expired.paymentId(), failed.paymentId(), failedTheDayBefore.paymentId()))
        .containsExactlyInAnyOrder(
            tuple("UNPAID_INVOICE", expired.paymentId(), "OPEN", buyer, AUTHORIZED, "USD"),
            tuple("UNPAID_INVOICE", failed.paymentId(), "OPEN", buyer, 300_000L, "USD"));
    assertThat(facts(expired.paymentId()).at("/invoice/id").asString())
        .isEqualTo(expired.invoiceId());
    assertThat(facts(expired.paymentId()).at("/payment/state").asString()).isEqualTo("EXPIRED");
    assertThat(facts(failed.paymentId()).at("/invoice/id").asString())
        .isEqualTo(failed.invoiceId());
    assertThat(facts(failed.paymentId()).at("/payment/state").asString()).isEqualTo("FAILED");
  }

  @Test
  void anApprovedAuthorizationExpiredByTheSevenDayRuleRaisesANeverClearedBreakNamingItsInvoice() {
    var buyerId = new BuyerId(UUID.randomUUID());
    var neverCleared = neverClearedCharge(buyerId);

    assertThat(run(today(), ISSUER_ADMIN)).hasStatus(201);

    assertThat(breaksNaming(neverCleared.requestId()))
        .containsExactly(
            tuple(
                "NEVER_CLEARED",
                neverCleared.requestId(),
                "OPEN",
                buyerId.value(),
                AUTHORIZED,
                "USD"));
    var facts = facts(neverCleared.requestId());
    assertThat(facts.at("/invoice/id").asString()).isEqualTo(neverCleared.invoiceId());
    assertThat(facts.at("/authorization/cardToken").asString()).isEqualTo(neverCleared.cardToken());
    // A re-run of the day finds it again, and adds none.
    assertThat(run(today(), ISSUER_ADMIN)).hasStatus(201);
    assertThat(breaksNaming(neverCleared.requestId())).hasSize(1);
  }

  // The Network settled more than the platform imported for one file and less for another; a third
  // file's Settlement matched.
  @Test
  void aMismatchedSettlementRaisesABreakNamingItsClearingFileWithTheGap() {
    var date = newBusinessDate();
    var above = clearingFile(date);
    settlement(above, 1_000_000, 940_000);
    var below = clearingFile(date);
    settlement(below, 500_000, 525_000);
    var matched = clearingFile(date);
    settlement(matched, 700_000, 700_000);

    assertThat(run(date, ISSUER_ADMIN)).hasStatus(201);

    // Only the Breaks naming these files: with every file settled, other tests' journal entries
    // may raise a books Break on this date too.
    assertThat(breaksNaming(above, below, matched))
        .containsExactlyInAnyOrder(
            tuple("MISMATCHED_SETTLEMENT", above, "OPEN", null, 60_000L, "USD"),
            tuple("MISMATCHED_SETTLEMENT", below, "OPEN", null, 25_000L, "USD"));
    var facts = facts(below);
    assertThat(facts.at("/settlement/networkFigure/minorUnits").asLong()).isEqualTo(500_000);
    assertThat(facts.at("/settlement/importedTotal/minorUnits").asLong()).isEqualTo(525_000);
    assertThat(facts.at("/clearingFile/id").asString()).isEqualTo(below);
    assertThat(facts.at("/clearingFile/businessDate").asString()).isEqualTo(date.toString());
  }

  @Test
  void aRunAgainRaisesNoNewBreakAndCountsTheEarlierOnesAsKnown() {
    var date = newBusinessDate();
    var fileId = clearingFile(date);
    clearing(fileId, null, 700_000, "USD", null, "UNKNOWN_CARD");
    clearing(
        fileId, card(new BuyerId(UUID.randomUUID())), 500_000, "USD", null, "NO_AUTHORIZATION");
    settlement(clearingFile(date), 1_000_000, 940_000);
    payment(new BuyerId(UUID.randomUUID()), "FAILED", 300_000, date);
    assertThat(run(date, ISSUER_ADMIN)).hasStatus(201);

    var again = run(date, ISSUER_ADMIN);

    assertThat(again).hasStatus(201);
    assertThat(again)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"businessDate": "%s", "breaksFound": 4, "newBreaks": 0, "knownBreaks": 4}
            """
                .formatted(date));
    assertThat(breaksOn(date)).hasSize(4);
  }

  @Test
  void onlyIssuerStaffMayRunAReconciliation() {
    assertProblem(run(newBusinessDate(), "ROLE_approver"), 403);
  }

  @Test
  void aRunWithoutABusinessDateIsABadRequest() {
    var response =
        mvc.post()
            .uri("/v1/reconciliation-runs")
            .with(jwt().authorities(new SimpleGrantedAuthority(ISSUER_ADMIN)))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}")
            .exchange();

    assertProblem(response, 400);
  }

  private MvcTestResult run(LocalDate businessDate, String authority) {
    return mvc.post()
        .uri("/v1/reconciliation-runs")
        .with(jwt().authorities(new SimpleGrantedAuthority(authority)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"businessDate\": \"%s\"}".formatted(businessDate))
        .exchange();
  }

  private static void assertProblem(MvcTestResult response, int status) {
    assertThat(response).hasStatus(status);
    assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(response).bodyJson().extractingPath("$.status").isEqualTo(status);
  }

  private static LocalDate newBusinessDate() {
    return LocalDate.of(2001, 1, 1).plusDays(2L * DAYS.getAndIncrement());
  }

  private List<Tuple> breaksOn(LocalDate date) {
    return jdbc.sql(
            """
            select kind, record_id, status, buyer_id, gap_minor, gap_currency
              from reconciliation.break where business_date = :date
            """)
        .param("date", Date.valueOf(date))
        .query(
            (row, n) ->
                tuple(
                    row.getString("kind"),
                    row.getString("record_id"),
                    row.getString("status"),
                    row.getObject("buyer_id", UUID.class),
                    row.getLong("gap_minor"),
                    row.getString("gap_currency")))
        .list();
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  /**
   * An Invoice and its Payment, by the IDs Breaks name them by, with the card and Authorization of
   * a never-cleared Charge; both null for a Payment with none.
   */
  private record Paid(String invoiceId, String paymentId, String cardToken, String requestId) {}

  // An approved Invoice and its Payment in this state, which ended on endedOn unless that is null.
  private Paid payment(BuyerId buyerId, String state, long amountMinor, LocalDate endedOn) {
    var invoiceId = UUID.randomUUID();
    var paymentId = UUID.randomUUID();
    var supplierId = UUID.randomUUID();
    jdbc.sql(
            """
            insert into payables.invoice
                   (id, buyer_id, supplier_id, invoice_number, amount_minor, currency, due_date,
                    status)
            values (:id, :buyerId, :supplierId, :number, :amount, 'USD', current_date, 'APPROVED')
            """)
        .param("id", invoiceId)
        .param("buyerId", buyerId.value())
        .param("supplierId", supplierId)
        .param("number", "INV-" + invoiceId)
        .param("amount", amountMinor)
        .update();
    jdbc.sql(
            """
            insert into payables.payment
                   (id, invoice_id, buyer_id, supplier_id, amount_minor, currency, state, ended_at)
            values (:id, :invoiceId, :buyerId, :supplierId, :amount, 'USD', :state, :endedAt)
            """)
        .param("id", paymentId)
        .param("invoiceId", invoiceId)
        .param("buyerId", buyerId.value())
        .param("supplierId", supplierId)
        .param("amount", amountMinor)
        .param("state", state)
        .param("endedAt", endedOn == null ? null : endedOn.atTime(12, 0).atOffset(ZoneOffset.UTC))
        .update();
    return new Paid(invoiceId.toString(), paymentId.toString(), null, null);
  }

  // A Payment whose card was charged 8 days ago, and the Charge never cleared: the expiry job, run
  // now, expires the card by the 7-day rule, and payables ends the Payment EXPIRED.
  private Paid neverClearedCharge(BuyerId buyerId) {
    var paid = payment(buyerId, "CARD_ISSUED", AUTHORIZED, null);
    var paymentId = UUID.fromString(paid.paymentId());
    var now = Instant.now();
    var chargedAt = now.minus(Duration.ofDays(8));
    var cardToken =
        cards
            .issueSingleUse(
                paymentId, buyerId, UUID.randomUUID(), null, new Money(AUTHORIZED, USD), chargedAt)
            .cardToken();
    var requestId = "N" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    jdbc.sql(
            """
            insert into "authorization".authorizations
                   (request_id, card_token, amount_minor, currency, acceptor_id, response_code,
                    authorization_code, status)
            values (:requestId, :cardToken, :amount, 'USD', 'ACQ1', '00', 'ABC123', 'APPROVED')
            """)
        .param("requestId", requestId)
        .param("cardToken", cardToken)
        .param("amount", AUTHORIZED)
        .update();
    assertThat(cards.authorize(cardToken, chargedAt)).isTrue();
    CardExpiryJob.runAt(context, now);
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                "EXPIRED"
                    .equals(
                        jdbc.sql("select state from payables.payment where id = :id")
                            .param("id", paymentId)
                            .query(String.class)
                            .single()));
    return new Paid(paid.invoiceId(), paid.paymentId(), cardToken, requestId);
  }

  private List<Tuple> breaksNaming(String... recordIds) {
    return jdbc.sql(
            """
            select kind, record_id, status, buyer_id, gap_minor, gap_currency
              from reconciliation.break where record_id in (:ids)
            """)
        .param("ids", List.of(recordIds))
        .query(
            (row, n) ->
                tuple(
                    row.getString("kind"),
                    row.getString("record_id"),
                    row.getString("status"),
                    row.getObject("buyer_id", UUID.class),
                    row.getLong("gap_minor"),
                    row.getString("gap_currency")))
        .list();
  }

  private JsonNode facts(String recordId) {
    return json.readTree(
        jdbc.sql("select facts::text from reconciliation.break where record_id = :id")
            .param("id", recordId)
            .query(String.class)
            .single());
  }

  // An imported Clearing File covering the date; returns its ID.
  private String clearingFile(LocalDate businessDate) {
    var fileId = "F" + UUID.randomUUID().toString().substring(0, 15);
    jdbc.sql(
            """
            insert into clearing.clearing_file
                   (id, sequence_number, business_date, status, record_count, imported_at)
            values (:id, 1, :date, 'IMPORTED', 0, now())
            """)
        .param("id", fileId)
        .param("date", Date.valueOf(businessDate))
        .update();
    return fileId;
  }

  // The file's Settlement at the Network's figure, beside the platform's imported total, as the
  // settle step records it; matched when the two agree.
  private void settlement(String fileId, long networkFigure, long importedTotal) {
    jdbc.sql(
            """
            insert into clearing.settlement
                   (file_id, amount_minor, imported_total_minor, currency, matched,
                    journal_entry_id)
            values (:fileId, :amount, :importedTotal, 'USD', :matched, :journalEntryId)
            """)
        .param("fileId", fileId)
        .param("amount", networkFigure)
        .param("importedTotal", importedTotal)
        .param("matched", networkFigure == importedTotal)
        .param("journalEntryId", UUID.randomUUID())
        .update();
  }

  private String card(BuyerId buyerId) {
    return card(buyerId, UUID.randomUUID());
  }

  private String card(BuyerId buyerId, UUID paymentId) {
    return cards
        .issueSingleUse(
            paymentId, buyerId, UUID.randomUUID(), null, new Money(AUTHORIZED, USD), Instant.now())
        .cardToken();
  }

  // A Clearing of the file, recorded with this outcome; its Authorization's request ID is "A" and
  // its Network record ID when it has one. Returns its Network record ID.
  private String clearing(
      String fileId,
      String cardToken,
      long clearedMinor,
      String clearedCurrency,
      Long authorizedMinor,
      String outcome) {
    var networkRecordId = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 19);
    jdbc.sql(
            """
            insert into clearing.clearing
                   (network_record_id, file_id, card_token, authorization_request_id,
                    cleared_amount_minor, cleared_currency, authorized_amount_minor,
                    authorized_currency, outcome, journal_entry_id)
            values (:id, :fileId, :cardToken, :authorizationRequestId, :cleared, :clearedCurrency,
                    :authorized, :authorizedCurrency, :outcome, :journalEntryId)
            """)
        .param("id", networkRecordId)
        .param("fileId", fileId)
        .param("cardToken", cardToken)
        .param("authorizationRequestId", authorizedMinor == null ? null : "A" + networkRecordId)
        .param("cleared", clearedMinor)
        .param("clearedCurrency", clearedCurrency)
        .param("authorized", authorizedMinor)
        .param("authorizedCurrency", authorizedMinor == null ? null : "USD")
        .param("outcome", outcome)
        .param("journalEntryId", UUID.randomUUID())
        .update();
    return networkRecordId;
  }
}
