package dev.rgonz.payments.loadtest;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.Simulation;
import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Acme's ERP submitting Invoices through the Gateway while the platform and the Gateway are
 * redeployed (M6 design, Proving it): {@code rate} (5) a second for {@code durationSeconds} (60),
 * each a new Invoice under its own {@code Idempotency-Key}. Anything but a {@code 201}, or no
 * answer within Gatling's 60-second request timeout, fails the call, and one failed call fails the
 * run.
 *
 * <p>An open workload, which Gatling's HTTP support allows, over connections the users share, as an
 * ERP's HTTP client keeps a pool of keep-alive connections: a redeploy then meets requests sent on
 * a connection to a pod that is stopping, which is what can go wrong.
 *
 * <p>Each Invoice is $10.00 to the load test's own Supplier, Hooli, and stays {@code RECEIVED}: no
 * one approves it, so it holds no credit and issues no card. Acme's threshold is $10,000.
 *
 * <p>Runs inside the k3d cluster beside {@link AuthorizeSimulation} ({@code
 * scripts/k8s-load-test.sh}), or from the host against the Gateway on {@code localhost:8080} or the
 * platform on Compose (README). {@code ACME_ERP_SECRET} in the environment signs it in as Acme's
 * ERP. System properties override the defaults: {@code baseUrl} ({@code http://localhost:8080}),
 * {@code rate}, {@code durationSeconds} and Keycloak's {@code tokenUri}.
 */
public class InvoiceSimulation extends Simulation {

  private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8080");
  private static final int RATE = Integer.getInteger("rate", 5);
  private static final int DURATION_SECONDS = Integer.getInteger("durationSeconds", 60);
  // Keycloak's tokens last 5 minutes, and the run uses the one it starts with.
  private static final int LONGEST_RUN_SECONDS = 240;

  // A Supplier no demo uses, so the run leaves the demo's Globex as it was.
  private static final String INVOICE =
      """
      {"supplier": {"name": "Hooli", "taxId": "LOAD-TEST-HOOLI", "buyerReference": "HOOLI-01",
                    "remittanceAddress": "http://localhost:8081/hooli/remittance-advice"},
       "invoiceNumber": "#{invoiceNumber}",
       "amount": {"minorUnits": 1000, "currency": "USD"},
       "dueDate": "#{dueDate}"}
      """;

  public InvoiceSimulation() throws IOException, InterruptedException {
    if (DURATION_SECONDS > LONGEST_RUN_SECONDS) {
      throw new IllegalStateException(
          "A run longer than %d s would outlive its token.".formatted(LONGEST_RUN_SECONDS));
    }
    var protocol =
        http.baseUrl(BASE_URL)
            .shareConnections()
            .contentTypeHeader("application/json")
            .acceptHeader("application/json")
            .authorizationHeader(
                "Bearer " + KeycloakTokens.clientCredentials("acme-erp", "ACME_ERP_SECRET"));

    var dueDate = LocalDate.now().plusDays(30).toString();
    var invoices =
        scenario("Submit Invoices")
            .exec(
                session ->
                    session
                        .set("idempotencyKey", UUID.randomUUID().toString())
                        .set("invoiceNumber", "LT-" + UUID.randomUUID())
                        .set("dueDate", dueDate))
            .exec(
                http("submit invoice")
                    .post("/v1/invoices")
                    .header("Idempotency-Key", "#{idempotencyKey}")
                    .body(StringBody(INVOICE))
                    .check(status().is(201)));
    setUp(invoices.injectOpen(constantUsersPerSec(RATE).during(DURATION_SECONDS)))
        .protocols(protocol)
        .assertions(global().failedRequests().count().is(0L));
  }
}
