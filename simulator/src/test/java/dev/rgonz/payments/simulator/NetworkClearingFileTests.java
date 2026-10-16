package dev.rgonz.payments.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import dev.rgonz.payments.contracts.client.model.Money;
import dev.rgonz.payments.simulator.GlobexSupplier.ReceivedCard;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
  private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 16);

  static final StubIssuer issuer = new StubIssuer();
  static final HttpServer keycloak = startStubKeycloak();
  static final Path dropFolder = createTempDirectory();

  @DynamicPropertySource
  static void pointAtStubs(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.client.provider.keycloak.token-uri",
        () -> "http://localhost:" + keycloak.getAddress().getPort() + "/token");
    registry.add("spring.security.oauth2.client.registration.network.client-secret", () -> "test");
    registry.add("simulator.platform-grpc-address", issuer::address);
    registry.add("simulator.network.drop-folder", dropFolder::toString);
  }

  @AfterAll
  static void stopStubs() throws IOException {
    issuer.stop();
    keycloak.stop(0);
    deleteFiles();
    Files.delete(dropFolder);
  }

  @Autowired GlobexAcquirer acquirer;
  @Autowired NetworkClearingFiles clearingFiles;

  // Each test starts with nothing left to send and an empty drop folder.
  @BeforeEach
  void startEmpty() throws IOException {
    issuer.reset();
    clearingFiles.write(BUSINESS_DATE);
    deleteFiles();
  }

  @Test
  void anApprovedChargeIsWrittenAsOneDetailRecordOfAFileFollowingTheLayout() throws IOException {
    issuer.answerWith("00", "AB12CD");
    acquirer.charge(card(1_240_000));
    var charge = issuer.received.getFirst().request();

    clearingFiles.write(BUSINESS_DATE);

    var lines = Files.readAllLines(onlyFile(), StandardCharsets.US_ASCII);
    assertThat(lines).hasSize(3);
    var header = Layout.read(lines.getFirst());
    assertThat(header.get("recordType")).isEqualTo("90");
    assertThat(header.get("fileId")).matches("[A-Za-z0-9-]{1,20}");
    assertThat(header.get("sequenceNumber")).matches("\\d{8}");
    assertThat(header.get("businessDate")).isEqualTo("20261016");
    var clearing = Layout.read(lines.get(1));
    var chargedAt = Instant.ofEpochSecond(charge.getTransmittedAt().getSeconds());
    assertThat(clearing)
        .containsEntry("recordType", "05")
        .containsEntry("pan", PAN)
        .containsEntry("amount", "000001240000")
        .containsEntry("currency", "USD")
        .containsEntry("retrievalReferenceNumber", charge.getRetrievalReferenceNumber())
        .containsEntry("authorizationCode", "AB12CD")
        .containsEntry("acceptorId", "GLOBEX000000001")
        .containsEntry(
            "chargeDate",
            LocalDate.ofInstant(chargedAt, ZoneOffset.UTC).toString().replace("-", ""));
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

    clearingFiles.write(BUSINESS_DATE);

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
    var first = dropFolder.resolve(clearingFiles.write(BUSINESS_DATE).orElseThrow());
    acquirer.charge(card(500_000));
    var secondCharge = issuer.received.getLast().request().getRetrievalReferenceNumber();

    var second = dropFolder.resolve(clearingFiles.write(BUSINESS_DATE.plusDays(1)).orElseThrow());

    var firstLines = Files.readAllLines(first, StandardCharsets.US_ASCII);
    var secondLines = Files.readAllLines(second, StandardCharsets.US_ASCII);
    var firstHeader = Layout.read(firstLines.getFirst());
    var secondHeader = Layout.read(secondLines.getFirst());
    assertThat(Long.parseLong(secondHeader.get("sequenceNumber")))
        .isEqualTo(Long.parseLong(firstHeader.get("sequenceNumber")) + 1);
    assertThat(secondHeader.get("fileId")).isNotEqualTo(firstHeader.get("fileId"));
    assertThat(secondHeader.get("businessDate")).isEqualTo("20261017");
    assertThat(secondLines).hasSize(3);
    assertThat(Layout.read(secondLines.get(1)))
        .containsEntry("retrievalReferenceNumber", secondCharge)
        .doesNotContainEntry(
            "networkRecordId", Layout.read(firstLines.get(1)).get("networkRecordId"));
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
      fileName = clearingFiles.write(BUSINESS_DATE).orElseThrow();
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

  @Test
  void withNothingToSendTheNetworkWritesNoFile() throws IOException {
    assertThat(clearingFiles.write(BUSINESS_DATE)).isEmpty();

    try (var files = Files.list(dropFolder)) {
      assertThat(files).isEmpty();
    }
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

  // The one file in the drop folder.
  private static Path onlyFile() throws IOException {
    try (var files = Files.list(dropFolder)) {
      var all = files.toList();
      assertThat(all).hasSize(1);
      return all.getFirst();
    }
  }

  private static void deleteFiles() throws IOException {
    try (var files = Files.walk(dropFolder)) {
      for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
        if (!file.equals(dropFolder)) {
          Files.delete(file);
        }
      }
    }
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

  private static Path createTempDirectory() {
    try {
      return Files.createTempDirectory("network-clearing-files");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
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
