package dev.rgonz.payments.platform.clearing;

import static dev.rgonz.payments.platform.clearing.TestClearingFiles.newId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc;
import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.cards.Cards;
import dev.rgonz.payments.platform.cards.SingleUseCard;
import dev.rgonz.payments.platform.clearing.TestClearingFiles.Detail;
import dev.rgonz.payments.platform.ledger.Holds;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import dev.rgonz.payments.platform.suppliers.SupplierOnInvoice;
import dev.rgonz.payments.platform.suppliers.Suppliers;
import dev.rgonz.payments.platform.vault.Vault;
import io.grpc.netty.NettyChannelBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Issuer staff import a Clearing File from the drop folder through the endpoint, on PostgreSQL:
 * each Clearing that matches its approved Authorization is posted to the journal, and its Hold and
 * its card become {@code CLEARED}. The cards are issued and charged the way the Payment saga and
 * the Network do it, the Charge over gRPC with a mocked Network token.
 */
@Import({TestcontainersConfiguration.class, ClearingFileImportTests.NetworkTokens.class})
@SpringBootTest
@AutoConfigureMockMvc
class ClearingFileImportTests {

  private static final Currency USD = Currency.getInstance("USD");
  private static final long CARD_AMOUNT = 1_240_000;
  private static final String ACCEPTOR_ID = "GLOBEX000000001";
  private static final DateTimeFormatter EXPIRATION_DATE =
      DateTimeFormatter.ofPattern("yyMM").withZone(ZoneOffset.UTC);
  private static final Path DROP_FOLDER = newDropFolder();

  /** Every bearer token on the gRPC port decodes as the Network's, so the test can charge cards. */
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

  @DynamicPropertySource
  static void dropFolder(DynamicPropertyRegistry registry) {
    registry.add("clearing.drop-folder", DROP_FOLDER::toString);
  }

  @Autowired MockMvcTester mvc;
  @Autowired JdbcClient jdbc;
  @Autowired Cards cards;
  @Autowired Holds holds;
  @Autowired Vault vault;
  @Autowired Suppliers suppliers;

  @LocalGrpcServerPort int grpcPort;

  @Test
  void aMatchedClearingBelowItsAuthorizationIsPostedAndClearsItsCardAndAllOfItsHold()
      throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var charged = chargedCard(buyerId);
    var fileId = newId("F", 16);
    var networkRecordId = newId("R", 20);
    var fileName =
        TestClearingFiles.drop(
            DROP_FOLDER, fileId, 7, List.of(clearingOf(charged, networkRecordId, 1_000_000)));

    var response = importFile(fileName, "ROLE_issuer-admin");

    assertThat(response).hasStatus(201);
    assertThat(response)
        .bodyJson()
        .isEqualTo(
            """
            {"id": "%s", "sequenceNumber": 7, "businessDate": "2026-10-15", "status": "IMPORTED",
             "recordCount": 1}
            """
                .formatted(fileId));
    var entryId = journalEntryFor(networkRecordId);
    assertThat(linesOf(entryId))
        .extracting(
            line -> line.get("kind"),
            line -> line.get("buyer_id"),
            line -> line.get("side"),
            line -> line.get("amount_minor"))
        .containsExactly(
            tuple("BUYER_RECEIVABLE", buyerId.value(), "DEBIT", 1_000_000L),
            tuple("NETWORK_SETTLEMENT_PAYABLE", null, "CREDIT", 1_000_000L));
    assertThat(holdStatus(charged.card())).isEqualTo("CLEARED");
    assertThat(cards.find(charged.card().cardToken()).orElseThrow().status())
        .isEqualTo(SingleUseCard.Status.CLEARED);
    // The Buyer Receivable counts the cleared amount instead of the whole Hold, so the rest of the
    // Hold is Available Credit again.
    assertThat(availableCredit(buyerId)).isEqualTo(10_000_000 - 1_000_000);
    assertThat(clearingRecorded(networkRecordId))
        .containsEntry("file_id", fileId)
        .containsEntry("card_token", charged.card().cardToken())
        .containsEntry("authorization_request_id", charged.requestId())
        .containsEntry("cleared_amount_minor", 1_000_000L)
        .containsEntry("authorized_amount_minor", CARD_AMOUNT)
        .containsEntry("currency", "USD")
        .containsEntry("outcome", "MATCHED")
        .containsEntry("journal_entry_id", entryId);
    assertThat(DROP_FOLDER.resolve(fileName)).doesNotExist();
    assertThat(DROP_FOLDER.resolve("processing").resolve(fileName)).doesNotExist();
  }

  @Test
  void aClearingWithoutAMatchingApprovedAuthorizationIsRecordedUnmatchedAndNotPosted()
      throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var charged = chargedCard(buyerId);
    var creditBefore = availableCredit(buyerId);
    var networkRecordId = newId("R", 20);
    var wrongCode =
        new Detail(
            networkRecordId, charged.pan(), 1_000_000, charged.requestId(), "ZZZZZZ", ACCEPTOR_ID);
    var fileName = TestClearingFiles.drop(DROP_FOLDER, newId("F", 16), 8, List.of(wrongCode));

    var response = importFile(fileName, "ROLE_issuer-admin");

    assertThat(response).hasStatus(201);
    assertThat(response).bodyJson().extractingPath("$.status").isEqualTo("IMPORTED");
    assertThat(clearingRecorded(networkRecordId))
        .containsEntry("card_token", charged.card().cardToken())
        .containsEntry("authorization_request_id", null)
        .containsEntry("outcome", "UNMATCHED")
        .containsEntry("journal_entry_id", null);
    assertThat(holdStatus(charged.card())).isEqualTo("PLACED");
    assertThat(cards.find(charged.card().cardToken()).orElseThrow().status())
        .isEqualTo(SingleUseCard.Status.AUTHORIZED);
    assertThat(availableCredit(buyerId)).isEqualTo(creditBefore);
  }

  @Test
  void aFileWithoutATrailerIsRefusedAndPostsNothing() throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var charged = chargedCard(buyerId);
    var creditBefore = availableCredit(buyerId);
    var fileId = newId("F", 16);
    var fileName =
        TestClearingFiles.write(
            DROP_FOLDER,
            List.of(
                TestClearingFiles.header(fileId, 9),
                TestClearingFiles.detail(clearingOf(charged, newId("R", 20), CARD_AMOUNT))));

    var response = importFile(fileName, "ROLE_issuer-admin");

    assertProblem(response, 422);
    assertThat(response)
        .bodyJson()
        .extractingPath("$.detail")
        .asString()
        .contains("Line 2: the file ends without a trailer (92).")
        .doesNotContain(charged.pan());
    assertThat(cards.find(charged.card().cardToken()).orElseThrow().status())
        .isEqualTo(SingleUseCard.Status.AUTHORIZED);
    assertThat(availableCredit(buyerId)).isEqualTo(creditBefore);
  }

  @Test
  void aFileNameNotInTheDropFolderIsNotFound() {
    var response = importFile("no-such-file.txt", "ROLE_issuer-admin");

    assertProblem(response, 404);
  }

  @Test
  void onlyIssuerStaffMayImportAFile() {
    var fileName = TestClearingFiles.drop(DROP_FOLDER, newId("F", 16), 10, List.of());

    var response = importFile(fileName, "ROLE_erp");

    assertProblem(response, 403);
    assertThat(DROP_FOLDER.resolve(fileName)).exists();
  }

  private static void assertProblem(MvcTestResult response, int status) {
    assertThat(response).hasStatus(status);
    assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(response).bodyJson().extractingPath("$.status").isEqualTo(status);
  }

  // A card issued as the Payment saga issues it (the Hold, then the card, for one Payment), and
  // charged for its whole amount by the Network, which the platform approved.
  private ChargedCard chargedCard(BuyerId buyerId) {
    var supplier =
        suppliers
            .relate(
                buyerId,
                new SupplierOnInvoice(
                    "TAX-" + UUID.randomUUID(),
                    "Globex",
                    "https://globex.example/remittance-advice",
                    "GLOBEX-01",
                    ACCEPTOR_ID))
            .supplier();
    var paymentId = UUID.randomUUID();
    var amount = new Money(CARD_AMOUNT, USD);
    assertThat(holds.place(paymentId, buyerId, amount)).isEqualTo(Holds.Outcome.PLACED);
    var card = cards.issueSingleUse(paymentId, buyerId, supplier.id(), amount, Instant.now());
    var details = vault.cardDetails(card.cardToken());
    var requestId = newId("Q", 12);
    var channel = NettyChannelBuilder.forAddress("127.0.0.1", grpcPort).usePlaintext().build();
    try {
      var response =
          IssuerAuthorizationGrpc.newBlockingStub(channel)
              .withDeadlineAfter(5, TimeUnit.SECONDS)
              .withInterceptors(new BearerTokenAuthenticationInterceptor("network-token"))
              .authorize(
                  AuthorizationRequest.newBuilder()
                      .setPrimaryAccountNumber(details.pan())
                      .setSecurityCode(details.securityCode())
                      .setExpirationDate(EXPIRATION_DATE.format(card.expiresAt()))
                      .setAmountMinor(CARD_AMOUNT)
                      .setCurrencyCode("USD")
                      .setRetrievalReferenceNumber(requestId)
                      .setCardAcceptorId(ACCEPTOR_ID)
                      .setCardAcceptorName("Globex, Springfield")
                      .build());
      assertThat(response.getResponseCode()).isEqualTo("00");
      return new ChargedCard(card, details.pan(), requestId, response.getAuthorizationCode());
    } finally {
      channel.shutdownNow();
    }
  }

  private record ChargedCard(
      SingleUseCard card, String pan, String requestId, String authorizationCode) {}

  // The Network's Clearing of the Charge, at its final amount.
  private static Detail clearingOf(ChargedCard charged, String networkRecordId, long amountMinor) {
    return new Detail(
        networkRecordId,
        charged.pan(),
        amountMinor,
        charged.requestId(),
        charged.authorizationCode(),
        ACCEPTOR_ID);
  }

  private MvcTestResult importFile(String fileName, String authority) {
    return mvc.post()
        .uri("/v1/clearing-files")
        .with(jwt().authorities(new SimpleGrantedAuthority(authority)))
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"fileName": "%s"}
            """
                .formatted(fileName))
        .exchange();
  }

  private UUID journalEntryFor(String networkRecordId) {
    return jdbc.sql(
            """
            select id from ledger.journal_entry
             where kind = 'CLEARING' and idempotency_key = :networkRecordId
            """)
        .param("networkRecordId", networkRecordId)
        .query(UUID.class)
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

  private String holdStatus(SingleUseCard card) {
    return jdbc.sql("select status from ledger.hold where id = :paymentId")
        .param("paymentId", card.paymentId())
        .query(String.class)
        .single();
  }

  private Map<String, Object> clearingRecorded(String networkRecordId) {
    return jdbc.sql(
            """
            select file_id, card_token, authorization_request_id, cleared_amount_minor,
                   authorized_amount_minor, currency, outcome, journal_entry_id
              from clearing.clearing
             where network_record_id = :networkRecordId
            """)
        .param("networkRecordId", networkRecordId)
        .query()
        .singleRow();
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

  private long availableCredit(BuyerId buyerId) throws IOException {
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

  private static Path newDropFolder() {
    try {
      return Files.createTempDirectory("clearing-drop-folder");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
