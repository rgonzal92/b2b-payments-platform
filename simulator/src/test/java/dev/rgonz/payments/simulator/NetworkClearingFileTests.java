package dev.rgonz.payments.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import dev.rgonz.payments.contracts.client.model.Money;
import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.simulator.GlobexSupplier.ReceivedCard;
import dev.rgonz.payments.simulator.NetworkClearingFiles.EdgeCase;
import dev.rgonz.payments.simulator.NetworkClearingFiles.WrittenFile;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The Network writes the Issuer's Clearing File for a Business Date with every Clearing Globex's
 * Acquirer submitted and the Network hasn't sent yet, into the drop folder, following the layout
 * contract in {@code contracts} (clearing-file/layout.md): against a stub Keycloak and a stub
 * {@code IssuerAuthorization} server.
 */
@SpringBootTest(properties = "simulator.seed.enabled=false")
class NetworkClearingFileTests {

  private static final String PAN = "9900001234567899";
  private static final DateTimeFormatter LAYOUT_DATE = DateTimeFormatter.BASIC_ISO_DATE;
  // No Charge in these tests is dated after it, so a file for it sends everything.
  private static final LocalDate AFTER_EVERY_CHARGE = LocalDate.of(9999, 12, 31);

  static final StubIssuer issuer = new StubIssuer();
  static final HttpServer keycloak = startStubKeycloak();
  @TempDir static Path dropFolder;

  @DynamicPropertySource
  static void pointAtStubs(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.client.provider.keycloak.token-uri",
        () -> "http://localhost:" + keycloak.getAddress().getPort() + "/token");
    registry.add("spring.security.oauth2.client.registration.network.client-secret", () -> "test");
    registry.add("simulator.platform-grpc-address", issuer::address);
    registry.add("simulator.network.drop-folder", () -> dropFolder.toString());
  }

  @AfterAll
  static void stopStubs() {
    issuer.stop();
    keycloak.stop(0);
  }

  @Autowired GlobexAcquirer acquirer;
  @Autowired NetworkClearingFiles clearingFiles;

  // Each test starts with nothing left to send and an empty drop folder.
  @BeforeEach
  void startEmpty() throws IOException {
    issuer.reset();
    clearingFiles.write(AFTER_EVERY_CHARGE);
    try (var files = Files.walk(dropFolder)) {
      for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
        if (!file.equals(dropFolder)) {
          Files.delete(file);
        }
      }
    }
  }

  @Test
  void anApprovedChargeIsWrittenAsOneDetailRecordOfAFileFollowingTheLayout() throws IOException {
    issuer.answerWith("00", "AB12CD");
    acquirer.charge(card(1_240_000));
    var charge = issuer.received.getFirst().request();
    var businessDate = chargeDate(charge);

    clearingFiles.write(businessDate);

    var lines = Files.readAllLines(onlyFile(), StandardCharsets.US_ASCII);
    assertThat(lines).hasSize(3);
    var header = Layout.read(lines.getFirst());
    assertThat(header.get("recordType")).isEqualTo("90");
    assertThat(header.get("fileId")).matches("[A-Za-z0-9-]{1,20}");
    assertThat(header.get("sequenceNumber")).matches("\\d{8}");
    assertThat(header.get("businessDate")).isEqualTo(businessDate.format(LAYOUT_DATE));
    var clearing = Layout.read(lines.get(1));
    assertThat(clearing)
        .containsEntry("recordType", "05")
        .containsEntry("pan", PAN)
        .containsEntry("amount", "000001240000")
        .containsEntry("currency", "USD")
        .containsEntry("retrievalReferenceNumber", charge.getRetrievalReferenceNumber())
        .containsEntry("authorizationCode", "AB12CD")
        .containsEntry("acceptorId", "GLOBEX000000001")
        .containsEntry("chargeDate", chargeDate(charge).format(LAYOUT_DATE));
    assertThat(clearing.get("networkRecordId")).matches("[A-Za-z0-9-]{1,20}");
    assertThat(Layout.read(lines.getLast()))
        .containsEntry("recordType", "92")
        .containsEntry("recordCount", "00000001")
        .containsEntry("amountTotal", "000000001240000")
        .containsEntry("netSettlementAmount", "000000001240000")
        .containsEntry("currency", "USD");
  }

  @Test
  void onlyApprovedChargesAreClearedNeitherADeclinedOneNorOneTheNetworkReversed()
      throws IOException {
    issuer.answerWith("00", "AB12CD");
    acquirer.charge(card(1_240_000));
    acquirer.charge(card(500_000));
    issuer.answerWith("57", "");
    acquirer.charge(card(300_000));
    // Approved after the Network's 1-second deadline: the Network declines it 91 and reverses it.
    issuer.answerWith("00", "LATE01");
    issuer.answerAfter(Duration.ofMillis(1_500));
    assertThat(acquirer.charge(card(200_000)).responseCode()).isEqualTo("91");
    await().atMost(Duration.ofSeconds(5)).until(() -> !issuer.reversals.isEmpty());
    var approved =
        issuer.received.subList(0, 2).stream()
            .map(received -> received.request().getRetrievalReferenceNumber())
            .toList();

    clearingFiles.write(AFTER_EVERY_CHARGE);

    var lines = Files.readAllLines(onlyFile(), StandardCharsets.US_ASCII);
    var clearings = lines.subList(1, lines.size() - 1).stream().map(Layout::read).toList();
    assertThat(clearings)
        .extracting(clearing -> clearing.get("retrievalReferenceNumber"))
        .containsExactlyElementsOf(approved);
    assertThat(clearings)
        .extracting(clearing -> clearing.get("amount"))
        .containsExactly("000001240000", "000000500000");
    assertThat(clearings)
        .extracting(clearing -> clearing.get("networkRecordId"))
        .doesNotHaveDuplicates();
    assertThat(Layout.read(lines.getLast()))
        .containsEntry("recordCount", "00000002")
        .containsEntry("amountTotal", "000000001740000")
        .containsEntry("netSettlementAmount", "000000001740000");
  }

  @Test
  void theNextFileCarriesTheNextSequenceNumberAndOnlyClearingsNotSentYet() throws IOException {
    acquirer.charge(card(1_240_000));
    var businessDate = chargeDate(issuer.received.getFirst().request());
    var first = dropFolder.resolve(clearingFiles.write(businessDate).orElseThrow());
    acquirer.charge(card(500_000));
    var secondRequestId = issuer.received.getLast().request().getRetrievalReferenceNumber();

    var second = dropFolder.resolve(clearingFiles.write(businessDate.plusDays(1)).orElseThrow());

    var firstLines = Files.readAllLines(first, StandardCharsets.US_ASCII);
    var secondLines = Files.readAllLines(second, StandardCharsets.US_ASCII);
    var firstHeader = Layout.read(firstLines.getFirst());
    var secondHeader = Layout.read(secondLines.getFirst());
    assertThat(Long.parseLong(secondHeader.get("sequenceNumber")))
        .isEqualTo(Long.parseLong(firstHeader.get("sequenceNumber")) + 1);
    assertThat(secondHeader.get("fileId")).isNotEqualTo(firstHeader.get("fileId"));
    assertThat(secondHeader.get("businessDate"))
        .isEqualTo(businessDate.plusDays(1).format(LAYOUT_DATE));
    assertThat(secondLines).hasSize(3);
    assertThat(Layout.read(secondLines.get(1)))
        .containsEntry("retrievalReferenceNumber", secondRequestId)
        .doesNotContainEntry(
            "networkRecordId", Layout.read(firstLines.get(1)).get("networkRecordId"));
  }

  // A file covers its Business Date: the file written just after midnight for the day that ended
  // leaves a Charge made since then for the next one.
  @Test
  void aClearingDatedAfterTheBusinessDateWaitsForALaterFile() throws IOException {
    acquirer.charge(card(1_240_000));
    var charge = issuer.received.getFirst().request();

    var dayBefore = clearingFiles.write(chargeDate(charge).minusDays(1));
    var chargeDay = clearingFiles.write(chargeDate(charge));

    assertThat(dayBefore).isEmpty();
    var lines =
        Files.readAllLines(dropFolder.resolve(chargeDay.orElseThrow()), StandardCharsets.US_ASCII);
    assertThat(Layout.read(lines.get(1)))
        .containsEntry("retrievalReferenceNumber", charge.getRetrievalReferenceNumber());
  }

  // The platform ignores names starting with '.', so it never reads a file still being written.
  @Test
  void theFileIsWrittenUnderANameStartingWithADotAndAppearsUnderItsOwnNameOnlyWhole()
      throws IOException, InterruptedException {
    acquirer.charge(card(1_240_000));
    var events = new ArrayList<String>();

    String fileName;
    try (var watcher = dropFolder.getFileSystem().newWatchService()) {
      dropFolder.register(
          watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
      fileName = clearingFiles.write(AFTER_EVERY_CHARGE).orElseThrow();
      WatchKey key;
      while ((key = watcher.poll(500, TimeUnit.MILLISECONDS)) != null) {
        key.pollEvents().forEach(event -> events.add(event.kind().name() + " " + event.context()));
        key.reset();
      }
    }

    assertThat(events).contains("ENTRY_CREATE ." + fileName);
    // A rename creates the file whole: no write ever touches it under its own name.
    assertThat(events)
        .filteredOn(event -> event.endsWith(" " + fileName))
        .containsExactly("ENTRY_CREATE " + fileName);
    try (var files = Files.list(dropFolder)) {
      assertThat(files).containsExactly(dropFolder.resolve(fileName));
    }
  }

  // The demo's edge cases (POST /demo/clearing-file's switches): each changes this one file, and
  // the trailer stays true to its records unless the switch is the wrong trailer total.

  @Test
  void aDuplicateRecordRepeatsTheFirstDetailRecordUnderItsNetworkRecordIdAndIsCounted()
      throws IOException {
    issuer.answerWith("00", "AB12CD");
    acquirer.charge(card(1_240_000));
    var charge = issuer.received.getFirst().request();

    var file = clearingFiles.write(AFTER_EVERY_CHARGE, EnumSet.of(EdgeCase.DUPLICATE_RECORD));

    var lines = linesOf(file);
    assertThat(lines).hasSize(4);
    assertThat(Layout.read(lines.get(1))).containsAllEntriesOf(clearingOf(charge, "AB12CD"));
    assertThat(lines.get(2)).isEqualTo(lines.get(1));
    assertThat(Layout.read(lines.getLast()))
        .containsEntry("recordCount", "00000002")
        .containsEntry("amountTotal", "000000002480000")
        .containsEntry("netSettlementAmount", "000000002480000");
  }

  @Test
  void anAmountAboveTheAuthorizedOneRaisesTheFirstClearingAloneAndTheTrailerTotalsIt()
      throws IOException {
    issuer.answerWith("00", "AB12CD");
    acquirer.charge(card(1_240_000));
    acquirer.charge(card(500_000));
    var first = issuer.received.getFirst().request();
    var second = issuer.received.getLast().request();

    var file =
        clearingFiles.write(AFTER_EVERY_CHARGE, EnumSet.of(EdgeCase.ABOVE_AUTHORIZED_AMOUNT));

    var lines = linesOf(file);
    assertThat(lines).hasSize(4);
    // $100.00 above the $12,400.00 the Issuer authorized.
    var raised = new HashMap<>(clearingOf(first, "AB12CD"));
    raised.put("amount", "000001250000");
    assertThat(Layout.read(lines.get(1))).containsAllEntriesOf(raised);
    assertThat(Layout.read(lines.get(2))).containsAllEntriesOf(clearingOf(second, "AB12CD"));
    assertThat(Layout.read(lines.getLast()))
        .containsEntry("recordCount", "00000002")
        .containsEntry("amountTotal", "000000001750000")
        .containsEntry("netSettlementAmount", "000000001750000");
  }

  @Test
  void aClearingWithNoAuthorizationIsAnExtraOneOnTheFirstClearingsCardUnderACodeNoApprovalHas()
      throws IOException {
    issuer.answerWith("00", "AB12CD");
    acquirer.charge(card(1_240_000));
    var charge = issuer.received.getFirst().request();

    var file = clearingFiles.write(AFTER_EVERY_CHARGE, EnumSet.of(EdgeCase.NO_AUTHORIZATION));

    var lines = linesOf(file);
    assertThat(lines).hasSize(4);
    var cleared = Layout.read(lines.get(1));
    assertThat(cleared).containsAllEntriesOf(clearingOf(charge, "AB12CD"));
    // The same card (one the Issuer issued), amount, Supplier and day, with its own Network record
    // ID and retrieval reference number, and an authorization code of four characters, where an
    // approval's has six (the .proto's DE 38).
    var extra = Layout.read(lines.get(2));
    var expected = new HashMap<>(clearingOf(charge, "NONE"));
    expected.remove("retrievalReferenceNumber");
    assertThat(extra).containsAllEntriesOf(expected);
    assertThat(extra.get("networkRecordId")).isNotEqualTo(cleared.get("networkRecordId"));
    assertThat(extra.get("retrievalReferenceNumber"))
        .hasSize(12)
        .isNotEqualTo(charge.getRetrievalReferenceNumber());
    assertThat(Layout.read(lines.getLast()))
        .containsEntry("recordCount", "00000002")
        .containsEntry("amountTotal", "000000002480000")
        .containsEntry("netSettlementAmount", "000000002480000");
    // Sent like any other Clearing: the next file doesn't carry it again.
    assertThat(clearingFiles.write(AFTER_EVERY_CHARGE)).isEmpty();
  }

  @Test
  void anUnknownPanIsAnExtraClearingOnALuhnValidPanTheIssuerNeverIssued() throws IOException {
    issuer.answerWith("00", "AB12CD");
    acquirer.charge(card(1_240_000));
    var charge = issuer.received.getFirst().request();

    var file = clearingFiles.write(AFTER_EVERY_CHARGE, EnumSet.of(EdgeCase.UNKNOWN_PAN));

    var lines = linesOf(file);
    assertThat(lines).hasSize(4);
    var cleared = Layout.read(lines.get(1));
    assertThat(cleared).containsAllEntriesOf(clearingOf(charge, "AB12CD"));
    // Like the first Clearing, with its own Network record ID and retrieval reference number, on a
    // PAN from the Issuer's made-up range, Luhn-valid, and of 19 digits where every PAN the Issuer
    // issues has 16 (the vault's Pans), so no card has it.
    var extra = Layout.read(lines.get(2));
    var expected = new HashMap<>(clearingOf(charge, "AB12CD"));
    expected.remove("pan");
    expected.remove("retrievalReferenceNumber");
    assertThat(extra).containsAllEntriesOf(expected);
    assertThat(extra.get("pan")).startsWith("990000").matches("\\d{19}");
    assertThat(luhnValid(extra.get("pan"))).isTrue();
    assertThat(extra.get("networkRecordId")).isNotEqualTo(cleared.get("networkRecordId"));
    assertThat(extra.get("retrievalReferenceNumber"))
        .isNotEqualTo(charge.getRetrievalReferenceNumber());
    assertThat(Layout.read(lines.getLast()))
        .containsEntry("recordCount", "00000002")
        .containsEntry("amountTotal", "000000002480000")
        .containsEntry("netSettlementAmount", "000000002480000");
  }

  @Test
  void aWrongTrailerTotalIsOneCentOffItsRecordsWhileTheirCountAndTheSettlementAmountStayTrue()
      throws IOException {
    issuer.answerWith("00", "AB12CD");
    acquirer.charge(card(1_240_000));
    var charge = issuer.received.getFirst().request();

    var file = clearingFiles.write(AFTER_EVERY_CHARGE, EnumSet.of(EdgeCase.WRONG_TRAILER_TOTAL));

    var lines = linesOf(file);
    assertThat(lines).hasSize(3);
    assertThat(Layout.read(lines.get(1))).containsAllEntriesOf(clearingOf(charge, "AB12CD"));
    assertThat(Layout.read(lines.getLast()))
        .containsEntry("recordCount", "00000001")
        .containsEntry("amountTotal", "000000001240001")
        .containsEntry("netSettlementAmount", "000000001240000");
  }

  @Test
  void aFileSentAgainArrivesUnderItsOwnNameAsItWasWrittenAndUsesNoSequenceNumber()
      throws IOException {
    acquirer.charge(card(1_240_000));
    var file = clearingFiles.write(AFTER_EVERY_CHARGE, EnumSet.noneOf(EdgeCase.class));
    var path = dropFolder.resolve(file.orElseThrow().name());
    var written = Files.readAllBytes(path);
    var sequenceNumber = sequenceNumberOf(linesOf(file));
    // The platform deletes a file once it has imported it.
    Files.delete(path);

    clearingFiles.sendAgain(file.orElseThrow());

    assertThat(onlyFile()).isEqualTo(path).hasBinaryContent(written);
    Files.delete(path);
    acquirer.charge(card(500_000));
    var next = clearingFiles.write(AFTER_EVERY_CHARGE, EnumSet.noneOf(EdgeCase.class));
    assertThat(sequenceNumberOf(linesOf(next))).isEqualTo(sequenceNumber + 1);
  }

  private static long sequenceNumberOf(List<String> lines) {
    return Long.parseLong(Layout.read(lines.getFirst()).get("sequenceNumber"));
  }

  @Test
  void theseTestsReadTheLayoutTheWayTheContractsSampleIsWritten() throws IOException {
    List<String> sample;
    try (InputStream in =
        Objects.requireNonNull(getClass().getResourceAsStream("/clearing-file/sample.txt"))) {
      sample = new String(in.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }

    assertThat(Layout.read(sample.getFirst()))
        .containsEntry("recordType", "90")
        .containsEntry("fileId", "NET-20261015-0001")
        .containsEntry("sequenceNumber", "00000042")
        .containsEntry("businessDate", "20261015");
    assertThat(Layout.read(sample.get(1)))
        .containsEntry("recordType", "05")
        .containsEntry("networkRecordId", "NR0000000001")
        .containsEntry("pan", "9900000000000010")
        .containsEntry("amount", "000001240000")
        .containsEntry("currency", "USD")
        .containsEntry("retrievalReferenceNumber", "A1B2C3D4E5F6")
        .containsEntry("authorizationCode", "X7Y8Z9")
        .containsEntry("acceptorId", "GLOBEX000000001")
        .containsEntry("chargeDate", "20261014");
    assertThat(Layout.read(sample.getLast()))
        .containsEntry("recordType", "92")
        .containsEntry("recordCount", "00000003")
        .containsEntry("amountTotal", "000000001752345")
        .containsEntry("netSettlementAmount", "000000001752345")
        .containsEntry("currency", "USD");
  }

  // The Luhn check: from the right, every second digit doubled, a doubled digit above 9 counting as
  // the sum of its digits; the digits of a valid number sum to a multiple of 10.
  private static boolean luhnValid(String digits) {
    var sum = 0;
    for (var i = 0; i < digits.length(); i++) {
      var digit = digits.charAt(digits.length() - 1 - i) - '0';
      sum += i % 2 == 0 ? digit : digit * 2 / 10 + digit * 2 % 10;
    }
    return sum % 10 == 0;
  }

  // The lines of a file the Network wrote, read back from the drop folder.
  private static List<String> linesOf(Optional<WrittenFile> file) throws IOException {
    return Files.readAllLines(
        dropFolder.resolve(file.orElseThrow().name()), StandardCharsets.US_ASCII);
  }

  // The fields of the detail record the Network writes for an approved Charge, as the layout reads
  // them, but its Network record ID.
  private static Map<String, String> clearingOf(
      AuthorizationRequest charge, String authorizationCode) {
    return Map.of(
        "recordType", "05",
        "pan", charge.getPrimaryAccountNumber(),
        "amount", "%012d".formatted(charge.getAmountMinor()),
        "currency", charge.getCurrencyCode(),
        "retrievalReferenceNumber", charge.getRetrievalReferenceNumber(),
        "authorizationCode", authorizationCode,
        "acceptorId", charge.getCardAcceptorId(),
        "chargeDate", chargeDate(charge).format(LAYOUT_DATE));
  }

  // The one file in the drop folder.
  private static Path onlyFile() throws IOException {
    try (var files = Files.list(dropFolder)) {
      var all = files.toList();
      assertThat(all).hasSize(1);
      return all.getFirst();
    }
  }

  // The day of the Charge, in UTC, from the time the Acquirer sent it.
  private static LocalDate chargeDate(AuthorizationRequest charge) {
    var sentAt = Instant.ofEpochSecond(charge.getTransmittedAt().getSeconds());
    return LocalDate.ofInstant(sentAt, ZoneOffset.UTC);
  }

  private static ReceivedCard card(long minorUnits) {
    return new ReceivedCard(
        "INV-42",
        PAN,
        "321",
        OffsetDateTime.parse("2026-11-15T12:00:00Z"),
        new Money().minorUnits(minorUnits).currency(Money.CurrencyEnum.USD));
  }

  /**
   * The layout contract's rules ({@code contracts}' clearing-file/layout.md), read independently of
   * the simulator's writer: every record 120 characters, its type in positions 1–2, numeric fields
   * digits only, alphanumeric fields left-justified and not blank, filler spaces.
   */
  static final class Layout {

    private record Field(String name, int from, int to, boolean numeric) {}

    private static final Map<String, List<Field>> FIELDS =
        Map.of(
            "90",
            List.of(an("fileId", 3, 22), n("sequenceNumber", 23, 30), n("businessDate", 31, 38)),
            "05",
            List.of(
                an("networkRecordId", 3, 22),
                an("pan", 23, 41),
                n("amount", 42, 53),
                an("currency", 54, 56),
                an("retrievalReferenceNumber", 57, 68),
                an("authorizationCode", 69, 74),
                an("acceptorId", 75, 89),
                n("chargeDate", 90, 97)),
            "92",
            List.of(
                n("recordCount", 3, 10),
                n("amountTotal", 11, 25),
                n("netSettlementAmount", 26, 40),
                an("currency", 41, 43)));

    // Where each record type's filler starts; it runs to position 120.
    private static final Map<String, Integer> FILLER = Map.of("90", 39, "05", 98, "92", 44);

    /** The record's fields by name, alphanumeric ones without their padding. */
    static Map<String, String> read(String line) {
      assertThat(line).hasSize(120);
      var type = line.substring(0, 2);
      assertThat(FIELDS).containsKey(type);
      var fields = new LinkedHashMap<String, String>();
      fields.put("recordType", type);
      for (var field : FIELDS.get(type)) {
        var value = line.substring(field.from() - 1, field.to());
        if (field.numeric()) {
          assertThat(value).as(field.name()).matches("\\d+");
          fields.put(field.name(), value);
        } else {
          assertThat(value).as(field.name()).doesNotStartWith(" ").isNotBlank();
          fields.put(field.name(), value.stripTrailing());
        }
      }
      assertThat(line.substring(FILLER.get(type) - 1)).as("filler").isBlank();
      return fields;
    }

    private static Field an(String name, int from, int to) {
      return new Field(name, from, to, false);
    }

    private static Field n(String name, int from, int to) {
      return new Field(name, from, to, true);
    }
  }

  // Answers every client with a token for the network client.
  private static HttpServer startStubKeycloak() {
    try {
      var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext(
          "/token",
          exchange -> {
            var bytes =
                """
                {"access_token": "token-for-network", "token_type": "Bearer", "expires_in": 300}
                """
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
