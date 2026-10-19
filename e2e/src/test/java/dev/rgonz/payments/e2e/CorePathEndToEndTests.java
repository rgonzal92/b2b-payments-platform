package dev.rgonz.payments.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.payments.platform.authorization.AuthorizationCopyFreshness;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonContent;
import org.springframework.web.client.RestClient;

/**
 * The core path the way the demo runs it: the platform and the simulator both running in this JVM,
 * against real PostgreSQL, Kafka and Keycloak (the repository's realm file), with
 * authorization-service running beside them from its executable jar, against its own PostgreSQL.
 * The service copies each card the platform issues from the {@code cards} topic and confirms it on
 * {@code authorizations}, which the platform's Payment waits for before sending the Remittance
 * Advice. The simulator's demo endpoint has Acme's ERP submit an Invoice from Globex, two of Acme's
 * Approvers approve it, the platform's worker pays it with a Single-Use Card, Globex opens the card
 * through its Remittance Advice link, and Globex charges it: the simulator's Acquirer and Network
 * ask authorization-service over gRPC, signed in as the network client, and the service approves
 * the Charge and tells the platform on {@code authorizations}. Then, once the platform's copy has
 * the approved Authorization, the demo's second call has the Network write its Clearing File into
 * the platform's drop folder and Issuer staff import it, and its third has Issuer staff run today's
 * Reconciliation, which finds no Break. Last, the simulator seeds a few load-test cards through the
 * same path.
 */
class CorePathEndToEndTests extends EndToEndHarness {

  @Test
  // The demo itself waits up to 30 seconds for the worker to pay the Invoice, and the seeding up
  // to two minutes for each of its Payments, though they take seconds.
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  void theDemoInvoiceIsPaidItsChargeApprovedClearedFromTheNetworksFileAndReconciledWithoutABreak()
      throws IOException {
    var summary =
        RestClient.create()
            .post()
            .uri("http://localhost:{port}/demo/invoices", simulatorPort)
            .retrieve()
            .body(String.class);

    var json = new JsonContent(summary);
    assertThat(json)
        .isLenientlyEqualTo(
            """
            {
              "amount": {"minorUnits": 1240000, "currency": "USD"},
              "approvedBy": ["service-account-acme-approver-1", "service-account-acme-approver-2"],
              "paymentState": "COMPLETED",
              "globexReceived": {"amount": {"minorUnits": 1240000, "currency": "USD"}},
              "authorization": {"approved": true, "responseCode": "00"}
            }
            """);
    String invoiceNumber = JsonPath.read(summary, "$.invoiceNumber");
    assertThat(json).extractingPath("$.globexReceived.invoiceNumber").isEqualTo(invoiceNumber);
    assertThat(json).extractingPath("$.globexReceived.lastFour").asString().matches("\\d{4}");
    assertThat(json).extractingPath("$.authorization.authorizationCode").asString().hasSize(6);
    // The service's approved Authorization reaches the platform's copy through its outbox and
    // Kafka a moment after the Network's answer. The demo imports once through the API, which
    // answers 503 while the copy lags (#94), and no lag check sees an event still in the service's
    // outbox: so the test waits until the copy has the Authorization and the platform's own check
    // finds no lag, its offset committed.
    String authorizationCode = JsonPath.read(summary, "$.authorization.authorizationCode");
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                platformHasApprovedAuthorization(authorizationCode)
                    && platform.getBean(AuthorizationCopyFreshness.class).caughtUp());
    // Until the Charge clears, its Hold reserves Acme's credit.
    assertThat(acmesCredit())
        .isLenientlyEqualTo(
            """
            {
              "openHolds": {"minorUnits": 1240000, "currency": "USD"},
              "buyerReceivable": {"minorUnits": 0, "currency": "USD"},
              "availableCredit": {"minorUnits": 8760000, "currency": "USD"}
            }
            """);

    var cleared =
        RestClient.create()
            .post()
            .uri("http://localhost:{port}/demo/clearing-file", simulatorPort)
            .retrieve()
            .body(String.class);

    // One import, the platform's 201 and its summary: the Charge's Clearing matched its
    // Authorization, which moves the card to CLEARED, and the file is settled at the Network's
    // figure, exactly what was posted.
    var clearedJson = new JsonContent(cleared);
    assertThat(clearedJson).extractingPath("$.imports").asArray().hasSize(1);
    assertThat(clearedJson)
        .isLenientlyEqualTo(
            """
            {"imports": [{
              "status": 201,
              "summary": {
                "status": "IMPORTED",
                "recordCount": 1,
                "skippedCount": 0,
                "outcomes": {"matched": 1, "aboveAuthorizedAmount": 0, "noAuthorization": 0,
                             "unknownCard": 0, "otherCurrency": 0,
                             "againstReversedAuthorization": 0, "afterHoldExpired": 0,
                             "authorizationAlreadyCleared": 0},
                "settlement": {
                  "amount": {"minorUnits": 1240000, "currency": "USD"},
                  "importedTotal": {"minorUnits": 1240000, "currency": "USD"},
                  "matched": true
                }
              }
            }]}
            """);
    String fileId = JsonPath.read(cleared, "$.imports[0].summary.id");
    assertThat(clearedJson).extractingPath("$.fileName").isEqualTo(fileId + ".txt");
    // The books, which no API shows until M9: the Clearing debited Acme's Buyer Receivable and
    // credited the Network Settlement Payable, and the Settlement was posted, paying that Payable
    // out of Issuer Cash.
    assertThat(balancesByAccountKind())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "BUYER_RECEIVABLE", 1_240_000L,
                "NETWORK_SETTLEMENT_PAYABLE", 0L,
                "ISSUER_CASH", -1_240_000L));
    // The Hold became posted spend: Acme owes the cleared amount, and its Available Credit counts
    // that instead of the Hold.
    assertThat(acmesCredit())
        .isLenientlyEqualTo(
            """
            {
              "openHolds": {"minorUnits": 0, "currency": "USD"},
              "buyerReceivable": {"minorUnits": 1240000, "currency": "USD"},
              "availableCredit": {"minorUnits": 8760000, "currency": "USD"}
            }
            """);
    // The platform deletes a file once it has imported it: the shared drop folder is empty again.
    try (var left = Files.walk(dropFolder)) {
      assertThat(left.filter(Files::isRegularFile)).isEmpty();
    }

    var reconciled =
        RestClient.create()
            .post()
            .uri("http://localhost:{port}/demo/reconciliation", simulatorPort)
            .retrieve()
            .body(String.class);

    // Today's Reconciliation finds nothing to raise: the Charge's Clearing matched its
    // Authorization at the full amount, the Settlement matched the file, and the books balance.
    // So no Break names the Charge, and none is open for today.
    assertThat(new JsonContent(reconciled))
        .isStrictlyEqualTo(
            """
            {
              "run": {
                "runId": "%s",
                "businessDate": "%s",
                "breaksFound": 0,
                "newBreaks": 0,
                "knownBreaks": 0
              },
              "openBreaks": []
            }
            """
                .formatted(
                    JsonPath.<String>read(reconciled, "$.run.runId"),
                    LocalDate.now(ZoneOffset.UTC)));

    // Last, after the Reconciliation it would change: the load test's cards, Initech's Invoices
    // paid through the same path, a card written for each.
    var seeded =
        RestClient.create()
            .post()
            .uri("http://localhost:{port}/demo/load-test-cards?count=5", simulatorPort)
            .retrieve()
            .body(String.class);

    assertThat(new JsonContent(seeded))
        .isLenientlyEqualTo(
            """
            {"requested": 5, "written": 5}
            """);
    // The header, then one line per card.
    assertThat(Files.readAllLines(loadTestFolder.resolve("cards.csv"))).hasSize(6);
  }

  // Each kind of Account's balance in the platform's journal, its debits less its credits.
  private static Map<String, Long> balancesByAccountKind() {
    return platform
        .getBean(JdbcClient.class)
        .sql(
            """
            select a.kind,
                   sum(case l.side when 'DEBIT' then l.amount_minor else -l.amount_minor end)
              from ledger.journal_line l
              join ledger.account a on a.id = l.account_id
             group by a.kind
            """)
        .query((row, rowNumber) -> Map.entry(row.getString(1), row.getLong(2)))
        .list()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  // Acme's credit, as Issuer staff read it: signed in through Keycloak as the simulator's
  // issuer-admin client.
  private static JsonContent acmesCredit() {
    String tokenResponse =
        RestClient.create()
            .post()
            .uri(keycloak.getAuthServerUrl() + "/realms/payments/protocol/openid-connect/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(
                "grant_type=client_credentials&client_id=simulator-issuer-admin&client_secret="
                    + CLIENT_SECRET)
            .retrieve()
            .body(String.class);
    String accessToken = JsonPath.read(tokenResponse, "$.access_token");
    var acme = simulator.getEnvironment().getRequiredProperty("simulator.acme.buyer-id");
    return new JsonContent(
        RestClient.create()
            .get()
            .uri("http://localhost:{port}/v1/buyers/{buyerId}/credit", platformPort, acme)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .retrieve()
            .body(String.class));
  }
}
