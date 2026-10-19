package dev.rgonz.payments.platform;

import static dev.rgonz.payments.platform.TestKeycloak.ACME_ID;
import static dev.rgonz.payments.platform.TestKeycloak.clientCredentialsToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.contracts.network.AuthorizationResponse;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc;
import dev.rgonz.payments.platform.clearing.TestClearingFiles;
import dev.rgonz.payments.platform.payables.CardOnFile;
import io.grpc.netty.NettyChannelBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.interceptor.security.BearerTokenAuthenticationInterceptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * M1's core path end to end, with real Keycloak tokens and PostgreSQL: Issuer staff set up Acme and
 * its Credit Line, Acme's ERP submits an Invoice above the Approval Threshold, two Approvers
 * approve it, the Payment saga's worker pays it on its schedule, and the Supplier opens its card
 * link once. Only the Supplier's remittance address is stubbed. The PAN check goes on to the
 * Network's Charge and its Clearing File.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMockRestServiceServer
class CorePathTests {

  private static final long APPROVAL_THRESHOLD = 1_000_000;
  // Above the Approval Threshold, so the Invoice takes two different Approvers.
  private static final long AMOUNT = 1_240_000;
  private static final String GLOBEX = "https://globex.example/remittance-advice";
  // Acme's ERP names it on the Invoice, and the Network carries it on Globex's Charge.
  private static final String GLOBEX_ACCEPTOR_ID = "GLOBEX000000001";

  private static final Path DROP_FOLDER = newDropFolder();

  @DynamicPropertySource
  static void issuer(DynamicPropertyRegistry registry) {
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestKeycloak::issuerUri);
    registry.add("clearing.drop-folder", DROP_FOLDER::toString);
  }

  @Autowired MockMvcTester mvc;
  @Autowired MockRestServiceServer supplier;
  @Autowired JdbcClient jdbc;
  @Autowired ApplicationContext context;

  @LocalGrpcServerPort int grpcPort;

  @Test
  void anInvoiceAboveTheThresholdIsApprovedTwicePaidAndItsCardShownToTheSupplierOnce()
      throws Exception {
    setUpAcme();
    var creditBefore = availableCredit();
    var deliveries = supplierAccepts();
    var erpToken = clientCredentialsToken("acme-erp");

    var invoiceId = submitInvoice(erpToken);
    var firstApproval = approve(invoiceId, "acme-approver-1");
    var secondApproval = approve(invoiceId, "acme-approver-2");
    awaitPaid(erpToken, invoiceId);

    assertThat(firstApproval).bodyJson().extractingPath("$.status").isEqualTo("RECEIVED");
    assertThat(secondApproval).bodyJson().extractingPath("$.status").isEqualTo("APPROVED");
    assertThat(availableCredit()).isEqualTo(creditBefore - AMOUNT);
    assertThat(deliveries).hasSize(1);
    assertThat(JsonPath.<String>read(deliveries.getFirst(), "$.invoiceId")).isEqualTo(invoiceId);
    var invoice = invoice(erpToken, invoiceId).getResponse().getContentAsString();
    String lastFour = JsonPath.read(invoice, "$.payment.card.lastFour");

    String link = JsonPath.read(deliveries.getFirst(), "$.cardLink");
    var opened = mvc.get().uri(link).exchange();
    var openedAgain = mvc.get().uri(link).exchange();

    assertThat(opened).hasStatusOk();
    assertThat(opened)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"amount": {"minorUnits": %d, "currency": "USD"}}
            """
                .formatted(AMOUNT));
    assertThat(JsonPath.<String>read(opened.getResponse().getContentAsString(), "$.pan"))
        .matches("990000\\d{10}")
        .endsWith(lastFour);
    assertThat(openedAgain).hasStatus(410);
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void noTableOutsideTheVaultAndNoLogLineHoldsThePanOnceTheCardIsChargedAndCleared(
      CapturedOutput output) throws Exception {
    setUpAcme();
    var deliveries = supplierAccepts();
    var erpToken = clientCredentialsToken("acme-erp");
    var invoiceId = submitInvoice(erpToken);
    approve(invoiceId, "acme-approver-1");
    approve(invoiceId, "acme-approver-2");
    awaitPaid(erpToken, invoiceId);
    var opened =
        mvc.get()
            .uri(JsonPath.<String>read(deliveries.getFirst(), "$.cardLink"))
            .exchange()
            .getResponse()
            .getContentAsString();
    String pan = JsonPath.read(opened, "$.pan");
    String securityCode = JsonPath.read(opened, "$.securityCode");
    // DE 14, the expiry month as printed on the card.
    var expirationDate =
        DateTimeFormatter.ofPattern("yyMM")
            .withZone(ZoneOffset.UTC)
            .format(OffsetDateTime.parse(JsonPath.read(opened, "$.expiresAt")));
    String cardToken =
        JsonPath.read(
            invoice(erpToken, invoiceId).getResponse().getContentAsString(),
            "$.payment.card.cardToken");
    String paymentId = JsonPath.read(deliveries.getFirst(), "$.id");
    // The Network charges the card with what the Supplier read: the PAN and security code pass
    // through authorization on their way to the vault.
    var requestId = TestClearingFiles.newId("Q", 12);
    var charged = charge(requestId, pan, securityCode, expirationDate);
    assertThat(charged.getResponseCode()).isEqualTo("00");
    // The Network clears the Charge in its Clearing File, PAN and all; Issuer staff import it, and
    // the PAN passes through clearing on its way to the vault.
    var fileId = TestClearingFiles.newId("F", 16);
    var networkRecordId = TestClearingFiles.newId("R", 20);
    var fileName =
        TestClearingFiles.drop(
            DROP_FOLDER,
            fileId,
            1,
            List.of(
                new TestClearingFiles.Detail(
                    networkRecordId,
                    pan,
                    AMOUNT,
                    requestId,
                    charged.getAuthorizationCode(),
                    GLOBEX_ACCEPTOR_ID)));
    var imported =
        mvc.post()
            .uri("/v1/clearing-files")
            .header("Authorization", "Bearer " + clientCredentialsToken("simulator-issuer-admin"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"fileName": "%s"}
                """
                    .formatted(fileName))
            .exchange();
    assertThat(imported).hasStatus(201);

    assertThat(columnsOutsideTheVaultHolding(pan)).isEmpty();
    assertThat(columnsOutsideTheVaultHoldingTheSecurityCode(securityCode)).isEmpty();
    // The same search finds a value that is stored outside the vault, such as the Card Token, in
    // clearing's and ledger's tables too, and reaches into the import job's saved step state.
    assertThat(columnsOutsideTheVaultHolding(cardToken))
        .contains(
            "cards.card.card_token",
            "suppliers.remittance_advice.card_token",
            "\"authorization\".authorizations.card_token",
            "clearing.clearing.card_token");
    assertThat(columnsOutsideTheVaultHolding(networkRecordId))
        .contains("clearing.clearing.network_record_id", "ledger.journal_entry.idempotency_key");
    assertThat(columnsOutsideTheVaultHolding("clearingRecordReader.read.count"))
        .contains("batch.batch_step_execution_context.short_context");
    // The worker's log lines, the gRPC call's and the import's, from their own threads, were
    // captured too.
    assertThat(output)
        .contains("Payment " + paymentId + " is COMPLETED")
        .contains("Authorization " + requestId + " answered 00")
        .contains("Clearing File " + fileId + " (sequence 1, Business Date 2026-10-15) imported")
        .doesNotContain(pan);
    // Three digits alone turn up in any log (timestamps, ports), so this looks for the security
    // code where a logged request, response or DTO would put it: after its name.
    assertThat(output).doesNotContainPattern(securityCodeAfterItsName(securityCode));
  }

  // Issuer staff create Acme and give it a Credit Line; the second test finds both already there.
  private void setUpAcme() {
    var issuerStaff = clientCredentialsToken("simulator-issuer-admin");
    var buyer =
        mvc.put()
            .uri("/v1/buyers/{buyerId}", ACME_ID)
            .header("Authorization", "Bearer " + issuerStaff)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name": "Acme", "approvalThreshold": {"minorUnits": %d, "currency": "USD"}}
                """
                    .formatted(APPROVAL_THRESHOLD))
            .exchange();
    assertThat(buyer).hasStatus2xxSuccessful();
    var creditLine =
        mvc.put()
            .uri("/v1/buyers/{buyerId}/credit-line", ACME_ID)
            .header("Authorization", "Bearer " + issuerStaff)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"minorUnits": 10000000, "currency": "USD"}
                """)
            .exchange();
    assertThat(creditLine).hasStatus2xxSuccessful();
  }

  // The Supplier's remittance address accepts one advice; the list records its body.
  private List<String> supplierAccepts() {
    var deliveries = new CopyOnWriteArrayList<String>();
    supplier
        .expect(requestTo(GLOBEX))
        .andExpect(method(HttpMethod.POST))
        .andExpect(request -> deliveries.add(((MockClientHttpRequest) request).getBodyAsString()))
        .andRespond(withSuccess());
    return deliveries;
  }

  private String submitInvoice(String erpToken) throws UnsupportedEncodingException {
    var invoiceNumber =
        "INV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    var submitted =
        mvc.post()
            .uri("/v1/invoices")
            .header("Authorization", "Bearer " + erpToken)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "supplier": {
                    "name": "Globex",
                    "taxId": "12-3456789",
                    "buyerReference": "GLOBEX-01",
                    "remittanceAddress": "%s",
                    "acceptorId": "%s"
                  },
                  "invoiceNumber": "%s",
                  "amount": {"minorUnits": %d, "currency": "USD"},
                  "dueDate": "2026-11-30"
                }
                """
                    .formatted(GLOBEX, GLOBEX_ACCEPTOR_ID, invoiceNumber, AMOUNT))
            .exchange();
    assertThat(submitted).hasStatus(201);
    return JsonPath.read(submitted.getResponse().getContentAsString(), "$.id");
  }

  private MvcTestResult approve(String invoiceId, String approver) {
    var approved =
        mvc.post()
            .uri("/v1/invoices/{invoiceId}/approval", invoiceId)
            .header("Authorization", "Bearer " + clientCredentialsToken(approver))
            .exchange();
    assertThat(approved).hasStatus(200);
    return approved;
  }

  private MvcTestResult invoice(String erpToken, String invoiceId) {
    var response =
        mvc.get()
            .uri("/v1/invoices/{invoiceId}", invoiceId)
            .header("Authorization", "Bearer " + erpToken)
            .exchange();
    assertThat(response).hasStatus(200);
    return response;
  }

  // The worker on its schedule issues the card, authorization-service (played here, with no Kafka)
  // has it on file, and the worker delivers the Remittance Advice.
  private void awaitPaid(String erpToken, String invoiceId) throws UnsupportedEncodingException {
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> "CARD_ISSUED".equals(paymentState(erpToken, invoiceId)));
    CardOnFile.confirm(
        context,
        JsonPath.read(
            invoice(erpToken, invoiceId).getResponse().getContentAsString(),
            "$.payment.card.cardToken"));
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> "COMPLETED".equals(paymentState(erpToken, invoiceId)));
  }

  // Null while the Invoice shows no Payment.
  private String paymentState(String erpToken, String invoiceId)
      throws UnsupportedEncodingException {
    return JsonPath.using(
            Configuration.defaultConfiguration().addOptions(Option.SUPPRESS_EXCEPTIONS))
        .parse(invoice(erpToken, invoiceId).getResponse().getContentAsString())
        .read("$.payment.state");
  }

  private long availableCredit() throws UnsupportedEncodingException {
    var response =
        mvc.get()
            .uri("/v1/buyers/{buyerId}/credit", ACME_ID)
            .header("Authorization", "Bearer " + clientCredentialsToken("simulator-issuer-admin"))
            .exchange();
    assertThat(response).hasStatus(200);
    return JsonPath.<Number>read(
            response.getResponse().getContentAsString(), "$.availableCredit.minorUnits")
        .longValue();
  }

  // The Network's Authorization request for the whole Invoice, signed in as the Network.
  private AuthorizationResponse charge(
      String requestId, String pan, String securityCode, String expirationDate) {
    var channel = NettyChannelBuilder.forAddress("127.0.0.1", grpcPort).usePlaintext().build();
    try {
      return IssuerAuthorizationGrpc.newBlockingStub(channel)
          .withDeadlineAfter(5, TimeUnit.SECONDS)
          .withInterceptors(
              new BearerTokenAuthenticationInterceptor(clientCredentialsToken("network")))
          .authorize(
              AuthorizationRequest.newBuilder()
                  .setPrimaryAccountNumber(pan)
                  .setSecurityCode(securityCode)
                  .setExpirationDate(expirationDate)
                  .setAmountMinor(AMOUNT)
                  .setCurrencyCode("USD")
                  .setRetrievalReferenceNumber(requestId)
                  .setCardAcceptorId(GLOBEX_ACCEPTOR_ID)
                  .setCardAcceptorName("Globex, Springfield")
                  .build());
    } finally {
      channel.shutdownNow();
    }
  }

  private static String securityCodeAfterItsName(String securityCode) {
    return "(?i)security.?code\\W{0,5}" + securityCode;
  }

  /**
   * Every column, in every table of every schema but the vault's (and PostgreSQL's own), holding
   * the value in any row: as text, which covers numbers too, and as raw bytes in a {@code bytea}.
   */
  private List<String> columnsOutsideTheVaultHolding(String value) {
    return columnsOutsideTheVaultWhere(
        column ->
            "bytea".equals(column.type())
                ? "position(convert_to(:value, 'UTF8') in " + column.name() + ") > 0"
                : "strpos(" + column.name() + "::text, :value) > 0",
        Map.of("value", value));
  }

  /**
   * Every column outside the vault holding the security code: as a text column's whole value, or
   * after its name anywhere, as a stored request or DTO would hold it. Three digits alone turn up
   * inside numbers, tokens and timestamps, so a plain substring search would find them everywhere.
   */
  private List<String> columnsOutsideTheVaultHoldingTheSecurityCode(String securityCode) {
    return columnsOutsideTheVaultWhere(
        column -> {
          var text =
              "bytea".equals(column.type())
                  ? "encode(" + column.name() + ", 'escape')"
                  : column.name() + "::text";
          var wholeValue =
              List.of("text", "character varying", "character").contains(column.type())
                  ? column.name() + " = :value or "
                  : "";
          return wholeValue + text + " ~ :afterItsName";
        },
        Map.of("value", securityCode, "afterItsName", securityCodeAfterItsName(securityCode)));
  }

  private List<String> columnsOutsideTheVaultWhere(
      Function<Column, String> condition, Map<String, ?> parameters) {
    var columns =
        jdbc.sql(
                """
                select format('%I.%I', c.table_schema, c.table_name), quote_ident(c.column_name),
                       c.data_type
                  from information_schema.columns c
                  join information_schema.tables t using (table_schema, table_name)
                 where t.table_type = 'BASE TABLE'
                   and c.table_schema not in ('vault', 'pg_catalog', 'information_schema')
                """)
            .query(
                (row, rowNumber) ->
                    new Column(row.getString(1), row.getString(2), row.getString(3)))
            .list();
    return columns.stream()
        .filter(
            column ->
                jdbc.sql(
                        "select exists (select 1 from %s where %s)"
                            .formatted(column.table(), condition.apply(column)))
                    .params(parameters)
                    .query(Boolean.class)
                    .single())
        .map(column -> column.table() + "." + column.name())
        .toList();
  }

  private record Column(String table, String name, String type) {}

  private static Path newDropFolder() {
    try {
      return Files.createTempDirectory("clearing-drop-folder");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
