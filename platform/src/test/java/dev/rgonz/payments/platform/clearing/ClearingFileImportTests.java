package dev.rgonz.payments.platform.clearing;

import static dev.rgonz.payments.platform.clearing.TestClearingFiles.newId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc;
import dev.rgonz.payments.contracts.network.ReversalRequest;
import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.cards.CardExpired;
import dev.rgonz.payments.platform.cards.CardExpiryJob;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.interceptor.security.BearerTokenAuthenticationInterceptor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.EventPublication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issuer staff import a Clearing File from the drop folder through the endpoint, on PostgreSQL:
 * every Clearing is posted to the journal and recorded with its outcome, its card becomes {@code
 * CLEARED}, and so does its Hold if still open. The cards are issued and charged the way the
 * Payment saga and the Network do it, the Charge over gRPC with a mocked Network token.
 */
@Import({TestcontainersConfiguration.class, ClearingFileImportTests.NetworkTokens.class})
@SpringBootTest
@AutoConfigureMockMvc
class ClearingFileImportTests {

  private static final Currency USD = Currency.getInstance("USD");
  private static final long CARD_AMOUNT = 1_240_000;
  private static final String ACCEPTOR_ID = "GLOBEX000000001";
  // In the platform's 990000 range, but 19 digits: the vault issues 16, so no card has it.
  private static final String UNKNOWN_PAN = "9900000000000000000";
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
  @Autowired ApplicationContext context;
  @Autowired TransactionTemplate transactions;
  @Autowired ApplicationEventPublisher events;
  @Autowired CompletedEventPublications completedEvents;

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
             "recordCount": 1,
             "outcomes": {"matched": 1, "aboveAuthorizedAmount": 0, "noAuthorization": 0,
                          "unknownCard": 0, "otherCurrency": 0, "againstReversedAuthorization": 0,
                          "afterHoldExpired": 0, "authorizationAlreadyCleared": 0}}
            """
                .formatted(fileId));
    var entryId = journalEntryFor(networkRecordId);
    assertThat(postingsOf(entryId))
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
  void aClearingWithNoAuthorizationIsPostedToItsCardsBuyerAndClearsTheCardAndItsHold()
      throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var charged = chargedCard(buyerId);
    var networkRecordId = newId("R", 20);
    var wrongCode =
        new Detail(
            networkRecordId, charged.pan(), 1_000_000, charged.requestId(), "ZZZZZZ", ACCEPTOR_ID);
    var fileName = TestClearingFiles.drop(DROP_FOLDER, newId("F", 16), 8, List.of(wrongCode));

    var response = importFile(fileName, "ROLE_issuer-admin");

    assertThat(response).hasStatus(201);
    assertThat(response).bodyJson().extractingPath("$.status").isEqualTo("IMPORTED");
    var entryId = journalEntryFor(networkRecordId);
    assertThat(postingsOf(entryId))
        .containsExactly(
            tuple("BUYER_RECEIVABLE", buyerId.value(), "DEBIT", 1_000_000L),
            tuple("NETWORK_SETTLEMENT_PAYABLE", null, "CREDIT", 1_000_000L));
    assertThat(clearingRecorded(networkRecordId))
        .containsEntry("card_token", charged.card().cardToken())
        .containsEntry("authorization_request_id", null)
        .containsEntry("authorized_amount_minor", null)
        .containsEntry("authorized_currency", null)
        .containsEntry("outcome", "NO_AUTHORIZATION")
        .containsEntry("journal_entry_id", entryId);
    assertThat(holdStatus(charged.card())).isEqualTo("CLEARED");
    assertThat(cards.find(charged.card().cardToken()).orElseThrow().status())
        .isEqualTo(SingleUseCard.Status.CLEARED);
    assertThat(availableCredit(buyerId)).isEqualTo(10_000_000 - 1_000_000);
  }

  @Test
  void aClearingForAPanTheVaultDoesntKnowIsPostedToClearingSuspense() {
    var networkRecordId = newId("R", 20);
    var unknownCard =
        new Detail(networkRecordId, UNKNOWN_PAN, 500_000, newId("Q", 12), "ZZZZZZ", ACCEPTOR_ID);
    var fileName = TestClearingFiles.drop(DROP_FOLDER, newId("F", 16), 13, List.of(unknownCard));

    var response = importFile(fileName, "ROLE_issuer-admin");

    assertThat(response).hasStatus(201);
    var entryId = journalEntryFor(networkRecordId);
    assertThat(postingsOf(entryId))
        .containsExactly(
            tuple("CLEARING_SUSPENSE", null, "DEBIT", 500_000L),
            tuple("NETWORK_SETTLEMENT_PAYABLE", null, "CREDIT", 500_000L));
    assertThat(clearingRecorded(networkRecordId))
        .containsEntry("card_token", null)
        .containsEntry("authorization_request_id", null)
        .containsEntry("cleared_amount_minor", 500_000L)
        .containsEntry("outcome", "UNKNOWN_CARD")
        .containsEntry("journal_entry_id", entryId);
  }

  @Test
  void aClearingInAnotherCurrencyThanItsCardsIsPostedToClearingSuspenseAndClearsTheCardAndHold()
      throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var charged = chargedCard(buyerId);
    var networkRecordId = newId("R", 20);
    var inEuros =
        new Detail(
            networkRecordId,
            charged.pan(),
            1_000_000,
            "EUR",
            charged.requestId(),
            charged.authorizationCode(),
            ACCEPTOR_ID);
    var fileName = TestClearingFiles.drop(DROP_FOLDER, newId("F", 16), 14, List.of(inEuros));

    var response = importFile(fileName, "ROLE_issuer-admin");

    assertThat(response).hasStatus(201);
    var entryId = journalEntryFor(networkRecordId);
    assertThat(postingsOf(entryId))
        .containsExactly(
            tuple("CLEARING_SUSPENSE", null, "DEBIT", 1_000_000L),
            tuple("NETWORK_SETTLEMENT_PAYABLE", null, "CREDIT", 1_000_000L));
    assertThat(clearingRecorded(networkRecordId))
        .containsEntry("card_token", charged.card().cardToken())
        .containsEntry("authorization_request_id", charged.requestId())
        .containsEntry("cleared_currency", "EUR")
        .containsEntry("authorized_currency", "USD")
        .containsEntry("outcome", "OTHER_CURRENCY")
        .containsEntry("journal_entry_id", entryId);
    assertThat(holdStatus(charged.card())).isEqualTo("CLEARED");
    assertThat(cards.find(charged.card().cardToken()).orElseThrow().status())
        .isEqualTo(SingleUseCard.Status.CLEARED);
    // Nothing is charged to the Buyer until a person resolves the Clearing, and the Hold is gone.
    assertThat(availableCredit(buyerId)).isEqualTo(10_000_000);
  }

  @Test
  void aClearingAboveItsAuthorizationIsPostedWholeToItsCardsBuyer() throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var charged = chargedCard(buyerId);
    var networkRecordId = newId("R", 20);
    var fileName =
        TestClearingFiles.drop(
            DROP_FOLDER,
            newId("F", 16),
            15,
            List.of(clearingOf(charged, networkRecordId, CARD_AMOUNT + 60_000)));

    var response = importFile(fileName, "ROLE_issuer-admin");

    assertThat(response).hasStatus(201);
    var entryId = journalEntryFor(networkRecordId);
    assertThat(postingsOf(entryId))
        .containsExactly(
            tuple("BUYER_RECEIVABLE", buyerId.value(), "DEBIT", CARD_AMOUNT + 60_000),
            tuple("NETWORK_SETTLEMENT_PAYABLE", null, "CREDIT", CARD_AMOUNT + 60_000));
    assertThat(clearingRecorded(networkRecordId))
        .containsEntry("authorization_request_id", charged.requestId())
        .containsEntry("cleared_amount_minor", CARD_AMOUNT + 60_000)
        .containsEntry("authorized_amount_minor", CARD_AMOUNT)
        .containsEntry("outcome", "ABOVE_AUTHORIZED_AMOUNT")
        .containsEntry("journal_entry_id", entryId);
    assertThat(holdStatus(charged.card())).isEqualTo("CLEARED");
    assertThat(cards.find(charged.card().cardToken()).orElseThrow().status())
        .isEqualTo(SingleUseCard.Status.CLEARED);
    // More than the Hold reserved: the Buyer owes all of it.
    assertThat(availableCredit(buyerId)).isEqualTo(10_000_000 - CARD_AMOUNT - 60_000);
  }

  @Test
  void aClearingAgainstAReversedAuthorizationIsPostedAndClearsTheActiveCardAndItsHold()
      throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var charged = chargedCard(buyerId);
    reverse(charged);
    var networkRecordId = newId("R", 20);
    var fileName =
        TestClearingFiles.drop(
            DROP_FOLDER,
            newId("F", 16),
            16,
            List.of(clearingOf(charged, networkRecordId, CARD_AMOUNT)));

    var response = importFile(fileName, "ROLE_issuer-admin");

    assertThat(response).hasStatus(201);
    var entryId = journalEntryFor(networkRecordId);
    assertThat(postingsOf(entryId))
        .containsExactly(
            tuple("BUYER_RECEIVABLE", buyerId.value(), "DEBIT", CARD_AMOUNT),
            tuple("NETWORK_SETTLEMENT_PAYABLE", null, "CREDIT", CARD_AMOUNT));
    assertThat(clearingRecorded(networkRecordId))
        .containsEntry("authorization_request_id", charged.requestId())
        .containsEntry("outcome", "AGAINST_REVERSED_AUTHORIZATION")
        .containsEntry("journal_entry_id", entryId);
    // The Reversal moved the card back to ACTIVE; its Hold stayed placed.
    assertThat(holdStatus(charged.card())).isEqualTo("CLEARED");
    assertThat(cards.find(charged.card().cardToken()).orElseThrow().status())
        .isEqualTo(SingleUseCard.Status.CLEARED);
    assertThat(availableCredit(buyerId)).isEqualTo(10_000_000 - CARD_AMOUNT);
  }

  @Test
  void aSecondClearingOfOneAuthorizationIsPostedAsAuthorizationAlreadyCleared() throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var charged = chargedCard(buyerId);
    var first = clearingOf(charged, newId("R", 20), 1_000_000);
    var second = clearingOf(charged, newId("R", 20), 200_000);
    var fileName = TestClearingFiles.drop(DROP_FOLDER, newId("F", 16), 17, List.of(first, second));

    var response = importFile(fileName, "ROLE_issuer-admin");

    assertThat(response).hasStatus(201);
    assertThat(clearingRecorded(first.networkRecordId())).containsEntry("outcome", "MATCHED");
    var entryId = journalEntryFor(second.networkRecordId());
    assertThat(postingsOf(entryId))
        .containsExactly(
            tuple("BUYER_RECEIVABLE", buyerId.value(), "DEBIT", 200_000L),
            tuple("NETWORK_SETTLEMENT_PAYABLE", null, "CREDIT", 200_000L));
    assertThat(clearingRecorded(second.networkRecordId()))
        .containsEntry("authorization_request_id", charged.requestId())
        .containsEntry("outcome", "AUTHORIZATION_ALREADY_CLEARED")
        .containsEntry("journal_entry_id", entryId);
    assertThat(availableCredit(buyerId)).isEqualTo(10_000_000 - 1_000_000 - 200_000);
  }

  @Test
  void aClearingAfterTheSevenDayRuleReleasedItsHoldIsPostedAndClearsTheExpiredCard()
      throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var charged = chargedCard(buyerId);
    CardExpiryJob.runAt(context, Instant.now().plus(Duration.ofDays(7)));
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> "RELEASED".equals(holdStatus(charged.card())));
    assertThat(cards.find(charged.card().cardToken()).orElseThrow().status())
        .isEqualTo(SingleUseCard.Status.EXPIRED);
    var networkRecordId = newId("R", 20);
    var fileName =
        TestClearingFiles.drop(
            DROP_FOLDER,
            newId("F", 16),
            18,
            List.of(clearingOf(charged, networkRecordId, CARD_AMOUNT)));

    var response = importFile(fileName, "ROLE_issuer-admin");

    assertThat(response).hasStatus(201);
    var entryId = journalEntryFor(networkRecordId);
    assertThat(postingsOf(entryId))
        .containsExactly(
            tuple("BUYER_RECEIVABLE", buyerId.value(), "DEBIT", CARD_AMOUNT),
            tuple("NETWORK_SETTLEMENT_PAYABLE", null, "CREDIT", CARD_AMOUNT));
    assertThat(clearingRecorded(networkRecordId))
        .containsEntry("authorization_request_id", charged.requestId())
        .containsEntry("outcome", "AFTER_HOLD_EXPIRED")
        .containsEntry("journal_entry_id", entryId);
    assertThat(holdStatus(charged.card())).isEqualTo("RELEASED");
    assertThat(cards.find(charged.card().cardToken()).orElseThrow().status())
        .isEqualTo(SingleUseCard.Status.CLEARED);
    assertThat(availableCredit(buyerId)).isEqualTo(10_000_000 - CARD_AMOUNT);
  }

  @Test
  void aClearingOnACancelledCardIsPostedToItsBuyerAndClearsTheCardAndItsHold() throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var card = issuedCard(buyerId);
    cards.cancel(card.cardToken());
    var networkRecordId = newId("R", 20);
    var clearing =
        new Detail(
            networkRecordId,
            vault.cardDetails(card.cardToken()).pan(),
            CARD_AMOUNT,
            newId("Q", 12),
            "ZZZZZZ",
            ACCEPTOR_ID);
    var fileName = TestClearingFiles.drop(DROP_FOLDER, newId("F", 16), 19, List.of(clearing));

    var response = importFile(fileName, "ROLE_issuer-admin");

    assertThat(response).hasStatus(201);
    var entryId = journalEntryFor(networkRecordId);
    assertThat(postingsOf(entryId))
        .containsExactly(
            tuple("BUYER_RECEIVABLE", buyerId.value(), "DEBIT", CARD_AMOUNT),
            tuple("NETWORK_SETTLEMENT_PAYABLE", null, "CREDIT", CARD_AMOUNT));
    assertThat(clearingRecorded(networkRecordId)).containsEntry("outcome", "NO_AUTHORIZATION");
    assertThat(holdStatus(card)).isEqualTo("CLEARED");
    assertThat(cards.find(card.cardToken()).orElseThrow().status())
        .isEqualTo(SingleUseCard.Status.CLEARED);
  }

  @Test
  void aClearingBeforeAnExpiredCardsHoldIsReleasedClearsTheHoldAndTheLateExpiryLeavesIt()
      throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var charged = chargedCard(buyerId);
    var card = charged.card();
    // What the 7-day rule leaves when it commits: the card EXPIRED, its CardExpired not yet
    // handled, so its Hold is still open.
    jdbc.sql("update cards.card set status = 'EXPIRED' where card_token = :cardToken")
        .param("cardToken", card.cardToken())
        .update();
    var networkRecordId = newId("R", 20);
    var fileName =
        TestClearingFiles.drop(
            DROP_FOLDER,
            newId("F", 16),
            20,
            List.of(clearingOf(charged, networkRecordId, 1_000_000)));

    var response = importFile(fileName, "ROLE_issuer-admin");

    assertThat(response).hasStatus(201);
    assertThat(clearingRecorded(networkRecordId)).containsEntry("outcome", "MATCHED");
    assertThat(holdStatus(card)).isEqualTo("CLEARED");
    assertThat(cards.find(card.cardToken()).orElseThrow().status())
        .isEqualTo(SingleUseCard.Status.CLEARED);
    var creditAfterClearing = availableCredit(buyerId);

    transactions.executeWithoutResult(
        status ->
            events.publishEvent(
                new CardExpired(
                    card.cardToken(), card.paymentId(), card.buyerId(), card.amount())));
    await().atMost(Duration.ofSeconds(10)).until(() -> completedExpiriesOf(card.cardToken()) == 1);

    assertThat(holdStatus(card)).isEqualTo("CLEARED");
    assertThat(creditAfterClearing).isEqualTo(10_000_000 - 1_000_000);
    assertThat(availableCredit(buyerId)).isEqualTo(creditAfterClearing);
  }

  @Test
  void theSummaryCountsTheFilesClearingsByOutcomeAndTheJournalStillBalances() throws Exception {
    var buyerId = buyerWithCreditLine(100_000_000);
    var clearings =
        List.of(
            clearingOf(chargedCard(buyerId), newId("R", 20), CARD_AMOUNT),
            clearingOf(chargedCard(buyerId), newId("R", 20), 1_000_000),
            clearingOf(chargedCard(buyerId), newId("R", 20), CARD_AMOUNT + 1),
            new Detail(
                newId("R", 20),
                chargedCard(buyerId).pan(),
                CARD_AMOUNT,
                newId("Q", 12),
                "ZZZZZZ",
                ACCEPTOR_ID),
            new Detail(
                newId("R", 20), UNKNOWN_PAN, 500_000, newId("Q", 12), "ZZZZZZ", ACCEPTOR_ID));
    var fileName = TestClearingFiles.drop(DROP_FOLDER, newId("F", 16), 21, clearings);

    var response = importFile(fileName, "ROLE_issuer-admin");

    assertThat(response).hasStatus(201);
    assertThat(response)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"recordCount": 5,
             "outcomes": {"matched": 2, "aboveAuthorizedAmount": 1, "noAuthorization": 1,
                          "unknownCard": 1, "otherCurrency": 0, "againstReversedAuthorization": 0,
                          "afterHoldExpired": 0, "authorizationAlreadyCleared": 0}}
            """);
    // Every Journal Entry balances, so the whole journal does: the Clearing Suspense posting too.
    var totals =
        jdbc.sql(
                """
                select coalesce(sum(amount_minor) filter (where side = 'DEBIT'), 0) as debits,
                       coalesce(sum(amount_minor) filter (where side = 'CREDIT'), 0) as credits
                  from ledger.journal_line
                """)
            .query()
            .singleRow();
    assertThat(totals.get("debits")).isEqualTo(totals.get("credits"));
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
    var card = issuedCard(buyerId);
    var details = vault.cardDetails(card.cardToken());
    var requestId = newId("Q", 12);
    var response =
        network()
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

  private SingleUseCard issuedCard(BuyerId buyerId) {
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
    return cards.issueSingleUse(paymentId, buyerId, supplier.id(), amount, Instant.now());
  }

  // The Network cancels the Charge's Authorization: the card is ACTIVE again.
  private void reverse(ChargedCard charged) {
    var response =
        network()
            .reverse(
                ReversalRequest.newBuilder()
                    .setRetrievalReferenceNumber(charged.requestId())
                    .build());
    assertThat(response.getResponseCode()).isEqualTo("00");
    assertThat(cards.find(charged.card().cardToken()).orElseThrow().status())
        .isEqualTo(SingleUseCard.Status.ACTIVE);
  }

  private IssuerAuthorizationGrpc.IssuerAuthorizationBlockingStub network() {
    return IssuerAuthorizationGrpc.newBlockingStub(channel)
        .withDeadlineAfter(5, TimeUnit.SECONDS)
        .withInterceptors(new BearerTokenAuthenticationInterceptor("network-token"));
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
  private List<Tuple> postingsOf(UUID entryId) {
    return jdbc.sql(
            """
            select a.kind, a.buyer_id, l.side, l.amount_minor
              from ledger.journal_line l
              join ledger.account a on a.id = l.account_id
             where l.entry_id = :entryId
             order by l.line_number
            """)
        .param("entryId", entryId)
        .query(
            (row, rowNumber) ->
                tuple(
                    row.getString("kind"),
                    row.getObject("buyer_id", UUID.class),
                    row.getString("side"),
                    row.getLong("amount_minor")))
        .list();
  }

  private long completedExpiriesOf(String cardToken) {
    return completedEvents.findAll().stream()
        .map(EventPublication::getEvent)
        .filter(
            event -> event instanceof CardExpired expired && expired.cardToken().equals(cardToken))
        .count();
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
