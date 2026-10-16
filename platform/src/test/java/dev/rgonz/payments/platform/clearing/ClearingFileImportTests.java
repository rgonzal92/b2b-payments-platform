package dev.rgonz.payments.platform.clearing;

import static dev.rgonz.payments.platform.clearing.TestClearingFiles.newId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc;
import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.cards.Cards;
import dev.rgonz.payments.platform.cards.SingleUseCard;
import dev.rgonz.payments.platform.clearing.TestClearingFiles.Detail;
import dev.rgonz.payments.platform.ledger.Holds;
import dev.rgonz.payments.platform.ledger.Journal;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import dev.rgonz.payments.platform.suppliers.SupplierOnInvoice;
import dev.rgonz.payments.platform.suppliers.Suppliers;
import dev.rgonz.payments.platform.vault.Vault;
import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
  @Autowired ClearingFileImports imports;
  @Autowired ClearingFiles files;

  // The real journal, which the restart test makes fail once.
  @MockitoSpyBean Journal journal;

  @LocalGrpcServerPort int grpcPort;

  private ManagedChannel channel;

  @BeforeEach
  void connect() {
    channel = NettyChannelBuilder.forAddress("127.0.0.1", grpcPort).usePlaintext().build();
  }

  @AfterEach
  void disconnect() {
    channel.shutdownNow();
  }

  @Test
  void anImportStoppedInItsSecondChunkRestartsAfterItsFirstAndPostsEveryClearingOnce()
      throws Exception {
    var buyerId = buyerWithCreditLine(1_000_000_000);
    var clearings = new ArrayList<Detail>();
    for (var i = 0; i < 250; i++) {
      clearings.add(clearingOf(chargedCard(buyerId), newId("R", 20), CARD_AMOUNT));
    }
    var creditBefore = availableCredit(buyerId);
    // Posting the 150th Clearing fails, once: partway through the second chunk of 100.
    var stopsAt = clearings.get(149).networkRecordId();
    var fault = new AtomicBoolean(true);
    doAnswer(
            posting -> {
              if (stopsAt.equals(posting.getArgument(0)) && fault.getAndSet(false)) {
                throw new IllegalStateException("Test fault: the import stops here.");
              }
              return posting.callRealMethod();
            })
        .when(journal)
        .postClearing(any(), any(), any());
    var fileName = TestClearingFiles.drop(DROP_FOLDER, newId("F", 16), 11, clearings);

    var stopped = importFile(fileName, "ROLE_issuer-admin");

    assertProblem(stopped, 500);
    // The first chunk committed; the second rolled back whole, and the file waits for a restart.
    assertThat(postedCount(clearings)).isEqualTo(100);
    assertThat(DROP_FOLDER.resolve("processing").resolve(fileName)).exists();

    var restarted = importFile(fileName, "ROLE_issuer-admin");

    assertThat(restarted).hasStatus(201);
    assertThat(restarted).bodyJson().extractingPath("$.recordCount").isEqualTo(250);
    assertThat(postedCount(clearings)).isEqualTo(250);
    assertThat(matchedCount(clearings)).isEqualTo(250);
    // Each Clearing debited the Buyer Receivable once, and cleared its card's Hold.
    assertThat(availableCredit(buyerId)).isEqualTo(creditBefore);
    assertThat(DROP_FOLDER.resolve("processing").resolve(fileName)).doesNotExist();
  }

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
             "recordCount": 1, "skippedCount": 0}
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
        .containsEntry("cleared_currency", "USD")
        .containsEntry("authorized_amount_minor", CARD_AMOUNT)
        .containsEntry("authorized_currency", "USD")
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
        .containsEntry("authorized_amount_minor", null)
        .containsEntry("authorized_currency", null)
        .containsEntry("outcome", "UNMATCHED")
        .containsEntry("journal_entry_id", null);
    assertThat(holdStatus(charged.card())).isEqualTo("PLACED");
    assertThat(cards.find(charged.card().cardToken()).orElseThrow().status())
        .isEqualTo(SingleUseCard.Status.AUTHORIZED);
    assertThat(availableCredit(buyerId)).isEqualTo(creditBefore);
  }

  // Each check of the validate step refuses the whole file: nothing is posted, the file waits in
  // failed/, and its summary and the answer say which check failed, never with the PAN.
  @ParameterizedTest(name = "{0}")
  @MethodSource("refusedFiles")
  void aFileThatFailsAValidateCheckIsRefusedWholeAndPostsNothing(
      String description, Function<Detail, List<String>> recordsAfterTheHeader, String reason)
      throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var charged = chargedCard(buyerId);
    var creditBefore = availableCredit(buyerId);
    var fileId = newId("F", 16);
    var clearing = clearingOf(charged, newId("R", 20), CARD_AMOUNT);
    var lines = new ArrayList<String>();
    lines.add(TestClearingFiles.header(fileId, 9));
    lines.addAll(recordsAfterTheHeader.apply(clearing));
    var fileName = TestClearingFiles.write(DROP_FOLDER, lines);

    var response = importFile(fileName, "ROLE_issuer-admin");

    assertProblem(response, 422);
    assertThat(response)
        .bodyJson()
        .extractingPath("$.detail")
        .asString()
        .contains(reason)
        .doesNotContain(charged.pan());
    assertThat(files.find(fileId))
        .get()
        .returns(ClearingFileSummary.Status.REFUSED, ClearingFileSummary::status)
        .returns(reason, ClearingFileSummary::refusalReason);
    assertThat(postedCount(List.of(clearing))).isZero();
    assertThat(cards.find(charged.card().cardToken()).orElseThrow().status())
        .isEqualTo(SingleUseCard.Status.AUTHORIZED);
    assertThat(availableCredit(buyerId)).isEqualTo(creditBefore);
    assertThat(DROP_FOLDER.resolve("failed").resolve(fileName)).exists();
    assertThat(DROP_FOLDER.resolve("processing").resolve(fileName)).doesNotExist();
  }

  static Stream<Arguments> refusedFiles() {
    return Stream.of(
        arguments(
            "a trailer whose record count isn't the file's",
            records(
                clearing ->
                    List.of(
                        TestClearingFiles.detail(clearing),
                        TestClearingFiles.trailer(
                            2, clearing.amountMinor(), clearing.amountMinor()))),
            "Line 3: the record count (positions 3–10) isn't the number of detail records."),
        arguments(
            "a trailer whose amount total isn't the file's",
            records(
                clearing ->
                    List.of(
                        TestClearingFiles.detail(clearing),
                        TestClearingFiles.trailer(
                            1, clearing.amountMinor() + 1, clearing.amountMinor() + 1))),
            "Line 3: the amount total (positions 11–25) isn't the sum of the detail records'"
                + " amounts."),
        arguments(
            "a malformed detail record",
            records(
                clearing -> {
                  var detail = TestClearingFiles.detail(clearing);
                  return List.of(
                      detail.substring(0, 41) + "TWELVE CENTS" + detail.substring(53),
                      TestClearingFiles.trailer(List.of(clearing)));
                }),
            "Line 2: the amount (positions 42–53) isn't a number."),
        arguments(
            "no trailer",
            records(clearing -> List.of(TestClearingFiles.detail(clearing))),
            "Line 2: the file ends without a trailer (92)."));
  }

  private static Function<Detail, List<String>> records(Function<Detail, List<String>> records) {
    return records;
  }

  @Test
  void anImportedFileIsDeletedAndItsHashAndHeaderAndTrailerFiguresAreKept() {
    var fileId = newId("F", 16);
    // The same content whatever its file ID, so its hash is known: `tail -c +23 <file> | sha256sum`
    // over this file gives the hash below. Its card is one the vault doesn't know. The Network
    // settles a figure of its own, which the trailer carries beside the amount total.
    var fileName =
        TestClearingFiles.write(
            DROP_FOLDER,
            List.of(
                TestClearingFiles.header(fileId, 15),
                TestClearingFiles.detail(
                    new Detail(
                        "NR-HASH-0000000001",
                        "9900000000000010",
                        125_000,
                        "HASH00000001",
                        "HASH01",
                        ACCEPTOR_ID)),
                TestClearingFiles.trailer(1, 125_000, 124_000)));

    assertThat(importFile(fileName, "ROLE_issuer-admin")).hasStatus(201);

    assertThat(DROP_FOLDER.resolve(fileName)).doesNotExist();
    assertThat(DROP_FOLDER.resolve("processing").resolve(fileName)).doesNotExist();
    assertThat(
            jdbc.sql(
                    """
                    select sequence_number, business_date, record_count, amount_total_minor,
                           net_settlement_amount_minor, totals_currency, content_hash
                      from clearing.clearing_file
                     where id = :id
                    """)
                .param("id", fileId)
                .query()
                .singleRow())
        .containsEntry("sequence_number", 15L)
        .containsEntry("business_date", Date.valueOf(LocalDate.of(2026, 10, 15)))
        .containsEntry("record_count", 1L)
        .containsEntry("amount_total_minor", 125_000L)
        .containsEntry("net_settlement_amount_minor", 124_000L)
        .containsEntry("totals_currency", "USD")
        .containsEntry(
            "content_hash", "eec2dfdddd1cdd636feec674c8dc18e1e6060fde00cd7fe6bec15afb7be660dc");
  }

  @Test
  void aFileWithTheIdOfAnImportedFileIsRefusedAndPostsNothingTwice() throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var imported = clearingOf(chargedCard(buyerId), newId("R", 20), CARD_AMOUNT);
    var notImported = clearingOf(chargedCard(buyerId), newId("R", 20), CARD_AMOUNT);
    var fileId = newId("F", 16);
    var first = TestClearingFiles.drop(DROP_FOLDER, fileId, 13, List.of(imported));
    assertThat(importFile(first, "ROLE_issuer-admin")).hasStatus(201);
    var creditBefore = availableCredit(buyerId);
    var again = TestClearingFiles.drop(DROP_FOLDER, fileId, 14, List.of(imported, notImported));

    var response = importFile(again, "ROLE_issuer-admin");

    assertProblem(response, 409);
    assertThat(response).bodyJson().extractingPath("$.detail").asString().contains(fileId);
    assertThat(postedCount(List.of(imported))).isEqualTo(1);
    assertThat(postedCount(List.of(notImported))).isZero();
    assertThat(availableCredit(buyerId)).isEqualTo(creditBefore);
    assertThat(DROP_FOLDER.resolve("failed").resolve(again)).exists();
    assertThat(DROP_FOLDER.resolve("processing").resolve(again)).doesNotExist();
  }

  @Test
  void theContentOfAnImportedFileUnderANewFileIdIsRefusedByItsHashAndPostsNothingTwice()
      throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var clearing = clearingOf(chargedCard(buyerId), newId("R", 20), CARD_AMOUNT);
    var earlierFileId = newId("F", 16);
    var first = TestClearingFiles.drop(DROP_FOLDER, earlierFileId, 16, List.of(clearing));
    assertThat(importFile(first, "ROLE_issuer-admin")).hasStatus(201);
    var creditBefore = availableCredit(buyerId);
    var newFileId = newId("F", 16);
    var again = TestClearingFiles.drop(DROP_FOLDER, newFileId, 16, List.of(clearing));

    var response = importFile(again, "ROLE_issuer-admin");

    assertProblem(response, 409);
    assertThat(response).bodyJson().extractingPath("$.detail").asString().contains(earlierFileId);
    assertThat(postedCount(List.of(clearing))).isEqualTo(1);
    assertThat(availableCredit(buyerId)).isEqualTo(creditBefore);
    assertThat(files.find(newFileId)).isEmpty();
    assertThat(DROP_FOLDER.resolve("failed").resolve(again)).exists();
    assertThat(DROP_FOLDER.resolve("processing").resolve(again)).doesNotExist();
  }

  @Test
  void aRecordWhoseNetworkRecordIdWasAlreadyImportedIsSkippedAndCountedNotPostedAgain()
      throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var repeated = clearingOf(chargedCard(buyerId), newId("R", 20), 1_000_000);
    var other = clearingOf(chargedCard(buyerId), newId("R", 20), 1_000_000);

    // Repeated within the first file, and again in the second.
    var first =
        importFile(
            TestClearingFiles.drop(DROP_FOLDER, newId("F", 16), 17, List.of(repeated, repeated)),
            "ROLE_issuer-admin");
    var second =
        importFile(
            TestClearingFiles.drop(DROP_FOLDER, newId("F", 16), 18, List.of(repeated, other)),
            "ROLE_issuer-admin");

    assertThat(first).hasStatus(201);
    assertThat(first).bodyJson().extractingPath("$.recordCount").isEqualTo(2);
    assertThat(first).bodyJson().extractingPath("$.skippedCount").isEqualTo(1);
    assertThat(second).hasStatus(201);
    assertThat(second).bodyJson().extractingPath("$.skippedCount").isEqualTo(1);
    assertThat(postedCount(List.of(repeated))).isEqualTo(1);
    assertThat(postedCount(List.of(other))).isEqualTo(1);
    // The Buyer Receivable holds each card's cleared amount once, and neither Hold counts.
    assertThat(availableCredit(buyerId)).isEqualTo(10_000_000 - 2 * 1_000_000);
  }

  @Test
  void theDropFolderCheckImportsANewFileAndLeavesOneStillBeingWritten() throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var charged = chargedCard(buyerId);
    var fileName =
        TestClearingFiles.drop(
            DROP_FOLDER,
            newId("F", 16),
            12,
            List.of(clearingOf(charged, newId("R", 20), CARD_AMOUNT)));
    // The Network writes under a name starting with ".", then renames the file (layout.md).
    var beingWritten = DROP_FOLDER.resolve(".clearing-" + UUID.randomUUID() + ".txt");
    Files.writeString(beingWritten, "90");

    imports.importNewFiles();

    assertThat(cards.find(charged.card().cardToken()).orElseThrow().status())
        .isEqualTo(SingleUseCard.Status.CLEARED);
    assertThat(DROP_FOLDER.resolve(fileName)).doesNotExist();
    assertThat(beingWritten).exists();
  }

  @Test
  void aFileNameNotInTheDropFolderIsNotFound() throws Exception {
    // The processing/ folder is a name in the drop folder too, but not a file.
    Files.createDirectories(DROP_FOLDER.resolve("processing"));

    for (var fileName : List.of("no-such-file.txt", "processing")) {
      assertProblem(importFile(fileName, "ROLE_issuer-admin"), 404);
    }
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

  // Journal Entries posted for these Clearings: one per Network record ID at most, by its key.
  private long postedCount(List<Detail> clearings) {
    return jdbc.sql(
            """
            select count(*) from ledger.journal_entry
             where kind = 'CLEARING' and idempotency_key in (:networkRecordIds)
            """)
        .param("networkRecordIds", clearings.stream().map(Detail::networkRecordId).toList())
        .query(Long.class)
        .single();
  }

  private long matchedCount(List<Detail> clearings) {
    return jdbc.sql(
            """
            select count(*) from clearing.clearing
             where outcome = 'MATCHED' and network_record_id in (:networkRecordIds)
            """)
        .param("networkRecordIds", clearings.stream().map(Detail::networkRecordId).toList())
        .query(Long.class)
        .single();
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
                   cleared_currency, authorized_amount_minor, authorized_currency, outcome,
                   journal_entry_id
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
