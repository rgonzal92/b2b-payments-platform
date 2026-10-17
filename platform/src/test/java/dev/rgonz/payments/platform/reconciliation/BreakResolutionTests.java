package dev.rgonz.payments.platform.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import dev.rgonz.payments.platform.TestcontainersConfiguration;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * A Reconciler resolves a Break over HTTP, on PostgreSQL. Each Break is written straight into
 * {@code reconciliation}'s table, as a run would have raised it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BreakResolutionTests {

  private static final String RECONCILER = "ROLE_reconciler";
  private static final String ACCEPT =
      """
      {"kind": "ACCEPTED", "note": "Agreed with the Buyer."}
      """;

  @Autowired MockMvcTester mvc;
  @Autowired JdbcClient jdbc;

  @Test
  void anAcceptedBreakIsResolvedAndMovesNoMoney() {
    var buyerId = UUID.randomUUID();
    var breakId = openBreak("UNDERCHARGE", buyerId, 240_000, "USD");

    var response =
        resolve(
            breakId,
            """
            {"kind": "ACCEPTED", "note": "The Buyer agreed to the lower amount."}
            """,
            RECONCILER);

    assertThat(response).hasStatus(200);
    assertThat(response)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {
              "id": "%s",
              "kind": "UNDERCHARGE",
              "status": "RESOLVED",
              "buyerId": "%s",
              "gap": {"minorUnits": 240000, "currency": "USD"},
              "resolution": {
                "kind": "ACCEPTED",
                "note": "The Buyer agreed to the lower amount.",
                "resolvedBy": "reconciler-subject",
                "resolvedByName": "rita"
              }
            }
            """
                .formatted(breakId, buyerId));
    assertThat(response).bodyJson().hasPathSatisfying("$.resolution.resolvedAt", at -> {});
    assertThat(response).bodyJson().extractingPath("$.resolution.correctingEntryId").isNull();
    assertThat(correctionsFor(breakId)).isZero();
  }

  @Test
  void aReassignedBreakMovesTheAmountFromClearingSuspenseToTheBuyerReceivable() {
    var buyerId = buyer();
    var breakId = openBreak("UNKNOWN_CARD", null, 700_000, "USD");
    var suspenseBefore = balance("CLEARING_SUSPENSE", null);

    var response =
        resolve(
            breakId,
            """
            {"kind": "REASSIGNED", "note": "The card was Acme's, reissued.", "buyerId": "%s",
             "amount": {"minorUnits": 700000, "currency": "USD"}}
            """
                .formatted(buyerId),
            RECONCILER);

    assertThat(response).hasStatus(200);
    assertThat(response)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"status": "RESOLVED",
             "resolution": {"kind": "REASSIGNED", "note": "The card was Acme's, reissued.",
                            "resolvedBy": "reconciler-subject"}}
            """);
    assertThat(response)
        .bodyJson()
        .extractingPath("$.resolution.correctingEntryId")
        .isEqualTo(correctionId(breakId).toString());
    assertThat(balance("BUYER_RECEIVABLE", buyerId)).isEqualTo(700_000);
    assertThat(balance("CLEARING_SUSPENSE", null)).isEqualTo(suspenseBefore - 700_000);
    assertJournalBalanced();
  }

  @Test
  void aCorrectedBreakPostsABalancedEntryBetweenTheNamedAccounts() {
    var buyerId = buyer();
    var breakId = openBreak("NO_AUTHORIZATION", buyerId, 500_000, "USD");
    var cashBefore = balance("ISSUER_CASH", null);

    // A Repayment recorded by mistake: debit the Buyer Receivable back, credit Issuer Cash.
    var response =
        resolve(
            breakId,
            """
            {"kind": "CORRECTED", "note": "Repayment REP-7 was recorded twice.",
             "debitAccount": {"kind": "BUYER_RECEIVABLE", "buyerId": "%s"},
             "creditAccount": {"kind": "ISSUER_CASH"},
             "amount": {"minorUnits": 125000, "currency": "USD"}}
            """
                .formatted(buyerId),
            RECONCILER);

    assertThat(response).hasStatus(200);
    assertThat(response)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"status": "RESOLVED",
             "resolution": {"kind": "CORRECTED", "note": "Repayment REP-7 was recorded twice.",
                            "resolvedBy": "reconciler-subject", "resolvedByName": "rita"}}
            """);
    assertThat(response)
        .bodyJson()
        .extractingPath("$.resolution.correctingEntryId")
        .isEqualTo(correctionId(breakId).toString());
    assertThat(balance("BUYER_RECEIVABLE", buyerId)).isEqualTo(125_000);
    assertThat(balance("ISSUER_CASH", null)).isEqualTo(cashBefore - 125_000);
    assertJournalBalanced();
  }

  @Test
  void aResolvedBreakCantBeResolvedAgain() {
    var breakId = openBreak("UNKNOWN_CARD", null, 700_000, "USD");
    var reassign =
        """
        {"kind": "REASSIGNED", "note": "Acme's card.", "buyerId": "%s",
         "amount": {"minorUnits": 700000, "currency": "USD"}}
        """
            .formatted(buyer());
    assertThat(resolve(breakId, reassign, RECONCILER)).hasStatus(200);

    assertProblem(resolve(breakId, reassign, RECONCILER), 409);
    assertThat(correctionsFor(breakId)).isOne();
  }

  @Test
  void issuerStaffCantResolveABreak() {
    var breakId = openBreak("UNDERCHARGE", UUID.randomUUID(), 1, "USD");

    assertProblem(resolve(breakId, ACCEPT, "ROLE_issuer-admin"), 403);
  }

  @Test
  void anUnknownBreakIsNotFound() {
    assertProblem(resolve(UUID.randomUUID(), ACCEPT, RECONCILER), 404);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        """
        {"kind": "ACCEPTED"}""",
        """
        {"kind": "ACCEPTED", "note": "  "}""",
        """
        {"kind": "REASSIGNED", "note": "n", "amount": {"minorUnits": 1, "currency": "USD"}}""",
        """
        {"kind": "REASSIGNED", "note": "n", "buyerId": "{buyer}"}""",
        """
        {"kind": "REASSIGNED", "note": "n", "buyerId": "{buyer}",
         "amount": {"minorUnits": 0, "currency": "USD"}}""",
        """
        {"kind": "REASSIGNED", "note": "n", "buyerId": "{buyer}",
         "amount": {"minorUnits": -5, "currency": "USD"}}""",
        """
        {"kind": "REASSIGNED", "note": "n", "buyerId": "{buyer}",
         "amount": {"minorUnits": 5, "currency": "XYZ"}}""",
        """
        {"kind": "REASSIGNED", "note": "n", "buyerId": "00000000-0000-0000-0000-000000000000",
         "amount": {"minorUnits": 5, "currency": "USD"}}""",
        """
        {"kind": "CORRECTED", "note": "n", "creditAccount": {"kind": "ISSUER_CASH"},
         "amount": {"minorUnits": 5, "currency": "USD"}}""",
        """
        {"kind": "CORRECTED", "note": "n", "debitAccount": {"kind": "ISSUER_CASH"},
         "creditAccount": {"kind": "ISSUER_CASH"}, "amount": {"minorUnits": 5, "currency": "USD"}}""",
        """
        {"kind": "CORRECTED", "note": "n", "debitAccount": {"kind": "BUYER_RECEIVABLE"},
         "creditAccount": {"kind": "ISSUER_CASH"}, "amount": {"minorUnits": 5, "currency": "USD"}}""",
        """
        {"kind": "CORRECTED", "note": "n", "debitAccount": {"kind": "CLEARING_SUSPENSE"},
         "creditAccount": {"kind": "ISSUER_CASH"}}""",
        // A Buyer Receivable in another currency than its Buyer's would be debt nothing counts.
        """
        {"kind": "REASSIGNED", "note": "n", "buyerId": "{buyer}",
         "amount": {"minorUnits": 5, "currency": "EUR"}}""",
        // A field the kind doesn't take is a mistake, not something to ignore.
        """
        {"kind": "ACCEPTED", "note": "n", "amount": {"minorUnits": 5, "currency": "USD"}}""",
        """
        {"kind": "REASSIGNED", "note": "n", "buyerId": "{buyer}",
         "debitAccount": {"kind": "ISSUER_CASH"}, "amount": {"minorUnits": 5, "currency": "USD"}}""",
        """
        {"kind": "CORRECTED", "note": "n", "buyerId": "{buyer}",
         "debitAccount": {"kind": "CLEARING_SUSPENSE"}, "creditAccount": {"kind": "ISSUER_CASH"},
         "amount": {"minorUnits": 5, "currency": "USD"}}"""
      })
  void anInvalidResolutionIsRefusedAndLeavesTheBreakOpen(String body) {
    var breakId = openBreak("UNKNOWN_CARD", null, 700_000, "USD");

    assertProblem(resolve(breakId, body.replace("{buyer}", buyer().toString()), RECONCILER), 422);
    assertThat(status(breakId)).isEqualTo("OPEN");
    assertThat(correctionsFor(breakId)).isZero();
  }

  @Test
  void ofTwoResolutionsAtOnceOneWinsAndTheOtherConflicts() throws Exception {
    var breakId = openBreak("UNKNOWN_CARD", null, 700_000, "USD");
    var reassign =
        """
        {"kind": "REASSIGNED", "note": "Acme's card.", "buyerId": "%s",
         "amount": {"minorUnits": 700000, "currency": "USD"}}
        """
            .formatted(buyer());
    var start = new CountDownLatch(1);
    try (var threads = Executors.newFixedThreadPool(2)) {
      var first =
          threads.submit(
              () -> {
                start.await();
                return resolve(breakId, reassign, RECONCILER).getResponse().getStatus();
              });
      var second =
          threads.submit(
              () -> {
                start.await();
                return resolve(breakId, reassign, RECONCILER).getResponse().getStatus();
              });
      start.countDown();

      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
    }
    assertThat(correctionsFor(breakId)).isOne();
    assertJournalBalanced();
  }

  private void assertProblem(MvcTestResult response, int status) {
    assertThat(response).hasStatus(status);
    assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
  }

  private String status(UUID breakId) {
    return jdbc.sql("select status from reconciliation.break where id = :id")
        .param("id", breakId)
        .query(String.class)
        .single();
  }

  private MvcTestResult resolve(UUID breakId, String body, String role) {
    return mvc.post()
        .uri("/v1/breaks/{breakId}/resolution", breakId)
        .with(
            jwt()
                .jwt(
                    token ->
                        token.subject("reconciler-subject").claim("preferred_username", "rita"))
                .authorities(new SimpleGrantedAuthority(role)))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  private UUID openBreak(String kind, UUID buyerId, long gapMinor, String currency) {
    var id = UUID.randomUUID();
    jdbc.sql(
            """
            insert into reconciliation.break
                   (id, kind, record_id, status, business_date, buyer_id, gap_minor, gap_currency,
                    facts)
            values (:id, :kind, :recordId, 'OPEN', :businessDate, :buyerId, :gapMinor, :currency,
                    '{}'::jsonb)
            """)
        .param("id", id)
        .param("kind", kind)
        .param("recordId", "R" + id)
        .param("businessDate", Date.valueOf(LocalDate.of(2026, 10, 1)))
        .param("buyerId", buyerId)
        .param("gapMinor", gapMinor)
        .param("currency", currency)
        .update();
    return id;
  }

  private UUID buyer() {
    var id = UUID.randomUUID();
    jdbc.sql(
            """
            insert into buyers.buyer (id, name, approval_threshold_minor, currency)
            values (:id, 'Acme', 1000000, 'USD')
            """)
        .param("id", id)
        .update();
    return id;
  }

  // An Account's USD balance, debits less credits; zero for one never posted to.
  private long balance(String kind, UUID buyerId) {
    return jdbc.sql(
            """
            select coalesce(sum(case l.side when 'DEBIT' then l.amount_minor
                                            else -l.amount_minor end), 0)
              from ledger.journal_line l
              join ledger.account a on a.id = l.account_id
             where a.kind = :kind and a.buyer_id is not distinct from :buyerId
               and a.currency = 'USD'
            """)
        .param("kind", kind)
        .param("buyerId", buyerId)
        .query(Long.class)
        .single();
  }

  private UUID correctionId(UUID breakId) {
    return jdbc.sql(
            """
            select id from ledger.journal_entry
             where kind = 'CORRECTION' and idempotency_key = :key
            """)
        .param("key", breakId.toString())
        .query(UUID.class)
        .single();
  }

  // Every Journal Entry's debits equal its credits.
  private void assertJournalBalanced() {
    var unbalanced =
        jdbc.sql(
                """
                select count(*) from (
                  select entry_id from ledger.journal_line
                   group by entry_id
                  having sum(case side when 'DEBIT' then amount_minor else 0 end)
                      <> sum(case side when 'CREDIT' then amount_minor else 0 end)) e
                """)
            .query(Long.class)
            .single();
    assertThat(unbalanced).isZero();
  }

  private long correctionsFor(UUID breakId) {
    return jdbc.sql(
            """
            select count(*) from ledger.journal_entry
             where kind = 'CORRECTION' and idempotency_key = :key
            """)
        .param("key", breakId.toString())
        .query(Long.class)
        .single();
  }
}
