package dev.rgonz.payments.platform.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Issuer staff record a Buyer's Repayment over HTTP, on PostgreSQL: it debits Issuer Cash and
 * credits the Buyer Receivable, so Available Credit rises by its amount.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RepaymentsApiTests {

  private static final String ISSUER_ADMIN = "ROLE_issuer-admin";

  @Autowired MockMvcTester mvc;
  @Autowired Journal journal;
  @Autowired JdbcClient jdbc;
  @Autowired DataSource dataSource;

  @Test
  void aRepaymentLowersTheBuyerReceivableAndRaisesAvailableCredit() throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    clearedCharge(buyerId, 1_240_000);

    var response = repay(buyerId, newKey(), usd(1_000_000));

    assertThat(response).hasStatus(201);
    assertThat(response)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"amount": {"minorUnits": 1000000, "currency": "USD"}}
            """);
    assertThat(response).bodyJson().extractingPath("$.postedAt").isNotNull();
    var repaymentId =
        UUID.fromString(JsonPath.read(response.getResponse().getContentAsString(), "$.id"));
    assertThat(linesOf(repaymentId))
        .extracting(
            line -> line.get("kind"),
            line -> line.get("buyer_id"),
            line -> line.get("side"),
            line -> line.get("amount_minor"))
        .containsExactly(
            tuple("ISSUER_CASH", null, "DEBIT", 1_000_000L),
            tuple("BUYER_RECEIVABLE", buyerId.value(), "CREDIT", 1_000_000L));
    assertThat(credit(buyerId))
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"buyerReceivable": {"minorUnits": 240000, "currency": "USD"},
             "availableCredit": {"minorUnits": 9760000, "currency": "USD"}}
            """);
  }

  @Test
  void aRepaymentAboveTheBuyerReceivableIsRefusedAndAllOfItIsTaken() throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    clearedCharge(buyerId, 1_240_000);

    var tooMuch = repay(buyerId, newKey(), usd(1_240_001));

    assertProblem(tooMuch, 422);
    assertThat(buyerReceivable(buyerId)).isEqualTo(1_240_000);

    var all = repay(buyerId, newKey(), usd(1_240_000));

    assertThat(all).hasStatus(201);
    assertThat(buyerReceivable(buyerId)).isZero();
  }

  @Test
  void aRepaymentOfZeroOrLessIsRefused() throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    clearedCharge(buyerId, 1_240_000);

    for (var minorUnits : List.of(0L, -1L)) {
      assertProblem(repay(buyerId, newKey(), usd(minorUnits)), 422);
    }
    assertThat(buyerReceivable(buyerId)).isEqualTo(1_240_000);
  }

  @Test
  void aRepaymentInAnotherCurrencyThanTheCreditLinesIsRefused() throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    clearedCharge(buyerId, 1_240_000);

    // EUR is a currency, just not this Credit Line's; XYZ isn't one at all.
    for (var currency : List.of("EUR", "XYZ")) {
      var body =
          """
          {"minorUnits": 100000, "currency": "%s"}
          """
              .formatted(currency);
      assertProblem(repay(buyerId, newKey(), body), 422);
    }
    assertThat(buyerReceivable(buyerId)).isEqualTo(1_240_000);
  }

  @Test
  void anUnknownBuyerOrOneWithoutACreditLineHasNothingToRepay() {
    var unknownBuyer = repay(new BuyerId(UUID.randomUUID()), newKey(), usd(100_000));
    var neverSet = repay(buyerWithoutCreditLine(), newKey(), usd(100_000));

    for (var response : List.of(unknownBuyer, neverSet)) {
      assertProblem(response, 404);
    }
  }

  @Test
  void onlyIssuerStaffRecordRepayments() throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    clearedCharge(buyerId, 1_240_000);

    for (var role : List.of("ROLE_erp", "ROLE_approver", "ROLE_network")) {
      var response =
          mvc.post()
              .uri("/v1/buyers/{buyerId}/repayments", buyerId.value())
              .with(as(role))
              .header("Idempotency-Key", newKey())
              .contentType(MediaType.APPLICATION_JSON)
              .content(usd(100_000))
              .exchange();
      assertProblem(response, 403);
    }
    assertThat(buyerReceivable(buyerId)).isEqualTo(1_240_000);
  }

  @Test
  void aRepaymentWithoutAnIdempotencyKeyIsRefused() throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    clearedCharge(buyerId, 1_240_000);

    var response =
        mvc.post()
            .uri("/v1/buyers/{buyerId}/repayments", buyerId.value())
            .with(as(ISSUER_ADMIN))
            .contentType(MediaType.APPLICATION_JSON)
            .content(usd(100_000))
            .exchange();

    assertProblem(response, 400);
    assertThat(buyerReceivable(buyerId)).isEqualTo(1_240_000);
  }

  @Test
  void theSameKeyTwicePostsOnceAndGetsTheSameAnswer() throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    clearedCharge(buyerId, 1_240_000);
    var key = newKey();
    // All of what the Buyer owes, so a second posting would be refused or leave a credit balance.
    var first = repay(buyerId, key, usd(1_240_000));

    var second = repay(buyerId, key, usd(1_240_000));

    assertThat(second).hasStatus(201);
    assertThat(second).bodyJson().isStrictlyEqualTo(first.getResponse().getContentAsString());
    assertThat(buyerReceivable(buyerId)).isZero();
  }

  @Test
  void theSameKeyWithAnotherAmountOrBuyerIsRefused() throws Exception {
    var acme = buyerWithCreditLine(10_000_000);
    var initech = buyerWithCreditLine(10_000_000);
    clearedCharge(acme, 1_240_000);
    clearedCharge(initech, 1_240_000);
    var key = newKey();
    repay(acme, key, usd(1_000_000));

    var otherAmount = repay(acme, key, usd(240_000));
    var otherBuyer = repay(initech, key, usd(1_000_000));

    for (var response : List.of(otherAmount, otherBuyer)) {
      assertProblem(response, 422);
    }
    assertThat(buyerReceivable(acme)).isEqualTo(240_000);
    assertThat(buyerReceivable(initech)).isEqualTo(1_240_000);
  }

  // A lock on the journal holds up both Repayments until both are running, so neither can post
  // before the other has started.
  @Test
  void ofTwoRepaymentsAtOnceThatTogetherExceedTheBalanceOnlyOneIsPosted() throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    clearedCharge(buyerId, 1_240_000);
    var statuses = new ArrayList<Integer>();

    // Closed in reverse order: the lock's connection rolls back before the executor waits.
    try (var issuerStaff = Executors.newFixedThreadPool(2);
        var journalLock = dataSource.getConnection()) {
      journalLock.setAutoCommit(false);
      try (var lock = journalLock.createStatement()) {
        lock.execute("lock table ledger.journal_entry in access exclusive mode");
      }
      var repayments =
          List.of(
              issuerStaff.submit(() -> repay(buyerId, newKey(), usd(1_000_000))),
              issuerStaff.submit(() -> repay(buyerId, newKey(), usd(1_000_000))));
      await().atMost(Duration.ofSeconds(5)).until(() -> lockWaiters() >= 2);

      journalLock.rollback();

      for (var repayment : repayments) {
        statuses.add(repayment.get(10, TimeUnit.SECONDS).getResponse().getStatus());
      }
    }

    assertThat(statuses).containsExactlyInAnyOrder(201, 422);
    assertThat(buyerReceivable(buyerId)).isEqualTo(240_000);
  }

  private BuyerId buyerWithCreditLine(long creditLine) {
    var buyerId = buyerWithoutCreditLine();
    var credit =
        mvc.put()
            .uri("/v1/buyers/{buyerId}/credit-line", buyerId.value())
            .with(as(ISSUER_ADMIN))
            .contentType(MediaType.APPLICATION_JSON)
            .content(usd(creditLine))
            .exchange();
    assertThat(credit).hasStatus(201);
    return buyerId;
  }

  private BuyerId buyerWithoutCreditLine() {
    var buyerId = UUID.randomUUID();
    var buyer =
        mvc.put()
            .uri("/v1/buyers/{buyerId}", buyerId)
            .with(as(ISSUER_ADMIN))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name": "Acme", "approvalThreshold": {"minorUnits": 1000000, "currency": "USD"}}
                """)
            .exchange();
    assertThat(buyer).hasStatus(201);
    return new BuyerId(buyerId);
  }

  // What a matched Clearing posts: the Buyer Receivable debited by the cleared amount.
  private void clearedCharge(BuyerId buyerId, long minorUnits) {
    journal.postClearing(
        UUID.randomUUID().toString(), buyerId, new Money(minorUnits, Currency.getInstance("USD")));
  }

  private MvcTestResult repay(BuyerId buyerId, String idempotencyKey, String body) {
    return mvc.post()
        .uri("/v1/buyers/{buyerId}/repayments", buyerId.value())
        .with(as(ISSUER_ADMIN))
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  private MvcTestResult credit(BuyerId buyerId) {
    var response =
        mvc.get()
            .uri("/v1/buyers/{buyerId}/credit", buyerId.value())
            .with(as(ISSUER_ADMIN))
            .exchange();
    assertThat(response).hasStatus(200);
    return response;
  }

  private long buyerReceivable(BuyerId buyerId) throws UnsupportedEncodingException {
    return JsonPath.<Number>read(
            credit(buyerId).getResponse().getContentAsString(), "$.buyerReceivable.minorUnits")
        .longValue();
  }

  private static void assertProblem(MvcTestResult response, int status) {
    assertThat(response).hasStatus(status);
    assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(response).bodyJson().extractingPath("$.status").isEqualTo(status);
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

  // Each line's Account (kind, and Buyer for a Buyer Receivable), side and amount, in order.
  private List<Map<String, Object>> linesOf(UUID entryId) {
    return jdbc.sql(
            """
            select a.kind, a.buyer_id, l.side, l.amount_minor
              from ledger.journal_line l
              join ledger.account a on a.id = l.account_id
             where l.entry_id = :entryId
             order by l.line_number
            """)
        .param("entryId", entryId)
        .query()
        .listOfRows();
  }

  private static JwtRequestPostProcessor as(String authority) {
    return jwt().authorities(new SimpleGrantedAuthority(authority));
  }

  private static String newKey() {
    return UUID.randomUUID().toString();
  }

  private static String usd(long minorUnits) {
    return """
        {"minorUnits": %d, "currency": "USD"}
        """
        .formatted(minorUnits);
  }
}
