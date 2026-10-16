package dev.rgonz.payments.simulator;

import dev.rgonz.payments.contracts.client.model.Money;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The Network's side of Clearing: keeps the Clearings Acquirers submit, in memory only, and writes
 * the Issuer's Clearing File for a Business Date with every Clearing not yet sent, following the
 * layout contract in {@code contracts} (clearing-file/layout.md), into the Issuer's drop folder.
 * The file holds PANs, as a Clearing File does; nothing logs them.
 */
@Component
class NetworkClearingFiles {

  private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
  private static final String ID_CHARACTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  // The demo's Clearing above its authorized amount: $100.00 more than the Charge.
  private static final long ABOVE_AUTHORIZED_BY = 10_000;
  // The demo's Clearing with no Authorization: an approval's authorization code has six characters
  // (DE 38 in issuer_authorization.proto), so no Authorization has this one.
  private static final String NO_AUTHORIZATION_CODE = "NONE";
  // The demo's unknown PAN: from the Issuer's made-up 990000 range, with its Luhn check digit, but
  // 19 digits long where every PAN the Issuer issues has 16, so no card has it.
  private static final String UNKNOWN_PAN = "9900000000000000002";
  private static final Logger log = LoggerFactory.getLogger(NetworkClearingFiles.class);

  private final Path dropFolder;
  private final SecureRandom random = new SecureRandom();
  private final List<Unsent> unsent = new ArrayList<>();
  private long sequenceNumber;

  // A String, not a Path: Spring converts a Path setting through its resource loader, which
  // resolves a relative path against the classpath.
  NetworkClearingFiles(@Value("${simulator.network.drop-folder}") String dropFolder) {
    this.dropFolder = Path.of(dropFolder).toAbsolutePath().normalize();
  }

  /**
   * Just after midnight UTC ({@code simulator.network.clearing-file-cron}), writes the file for the
   * Business Date that just ended; the platform's check of its drop folder imports it.
   */
  @Scheduled(cron = "${simulator.network.clearing-file-cron}", zone = "UTC")
  void writeTheDayThatEnded() {
    write(LocalDate.now(ZoneOffset.UTC).minusDays(1));
  }

  /**
   * Takes a Clearing an Acquirer submitted, for the next file, under a Network record ID of its
   * own. Its detail record is written now, so a Clearing that doesn't fit the layout is refused
   * here ({@link IllegalArgumentException}) rather than holding up every file after it.
   */
  synchronized void submit(Clearing clearing) {
    unsent.add(Unsent.of(newNetworkRecordId(), clearing));
  }

  /**
   * Writes the Clearing File for this Business Date with every Clearing not yet sent, and returns
   * its name in the drop folder. A Clearing dated after the Business Date waits for a later file.
   * Writes nothing and returns empty when there is nothing to send.
   */
  Optional<String> write(LocalDate businessDate) {
    return write(businessDate, Set.of()).map(WrittenFile::name);
  }

  /** Writes the file as {@link #write(LocalDate)} does, changed by the demo's edge cases. */
  synchronized Optional<WrittenFile> write(LocalDate businessDate, Set<EdgeCase> edgeCases) {
    var sending =
        unsent.stream().filter(clearing -> !clearing.chargeDate().isAfter(businessDate)).toList();
    if (sending.isEmpty()) {
      return Optional.empty();
    }
    var records = new ArrayList<>(sending);
    if (edgeCases.contains(EdgeCase.ABOVE_AUTHORIZED_AMOUNT)) {
      records.set(0, records.getFirst().raisedBy(ABOVE_AUTHORIZED_BY));
    }
    var first = sending.getFirst().clearing();
    if (edgeCases.contains(EdgeCase.NO_AUTHORIZATION)) {
      records.add(
          Unsent.of(
              newNetworkRecordId(),
              new Clearing(
                  first.pan(),
                  first.amount(),
                  randomId(12),
                  NO_AUTHORIZATION_CODE,
                  first.acceptorId(),
                  first.chargeDate())));
    }
    if (edgeCases.contains(EdgeCase.UNKNOWN_PAN)) {
      records.add(
          Unsent.of(
              newNetworkRecordId(),
              new Clearing(
                  UNKNOWN_PAN,
                  first.amount(),
                  randomId(12),
                  first.authorizationCode(),
                  first.acceptorId(),
                  first.chargeDate())));
    }
    if (edgeCases.contains(EdgeCase.DUPLICATE_RECORD)) {
      records.add(records.getFirst());
    }
    var fileId = "NET-" + businessDate.format(DATE) + "-" + randomId(7);
    // One currency, USD, the only one the contract's Money has.
    var currency = records.getFirst().amount().getCurrency().getValue();
    var total = records.stream().mapToLong(clearing -> clearing.amount().getMinorUnits()).sum();
    var file = new StringBuilder();
    file.append(record("90", an(fileId, 20), n(sequenceNumber + 1, 8), businessDate.format(DATE)));
    records.forEach(clearing -> file.append(clearing.detailRecord()));
    // The simulator charges no fees: the net settlement amount is the records' total.
    var amountTotal = edgeCases.contains(EdgeCase.WRONG_TRAILER_TOTAL) ? total + 1 : total;
    file.append(
        record("92", n(records.size(), 8), n(amountTotal, 15), n(total, 15), an(currency, 3)));
    var written = new WrittenFile(fileId + ".txt", file.toString());
    deliver(written);
    sequenceNumber++;
    unsent.removeAll(sending);
    log.info(
        "The Network wrote Clearing File {} for {}: {} Clearings, {} {} minor units",
        written.name(),
        businessDate,
        records.size(),
        currency,
        total);
    return Optional.of(written);
  }

  /**
   * Delivers a file the Network wrote to the drop folder again, under its own name and exactly as
   * it was: the same file sent twice. It uses no new sequence number.
   */
  void sendAgain(WrittenFile file) {
    deliver(file);
    log.info("The Network sent Clearing File {} again", file.name());
  }

  // Written under a name starting with '.', which the platform ignores, then renamed in one step:
  // the platform never reads half a file (the layout's delivery rule).
  private void deliver(WrittenFile file) {
    var partial = dropFolder.resolve("." + file.name());
    try {
      Files.createDirectories(dropFolder);
      Files.writeString(partial, file.content(), StandardCharsets.US_ASCII);
      Files.move(partial, dropFolder.resolve(file.name()), StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      deleteHalfWritten(partial, e);
      throw new UncheckedIOException(e);
    }
  }

  // The half-written file holds PANs: it mustn't stay behind. The Clearings stay unsent, for the
  // next file.
  private static void deleteHalfWritten(Path partial, IOException cause) {
    try {
      Files.deleteIfExists(partial);
    } catch (IOException notDeleted) {
      cause.addSuppressed(notDeleted);
    }
  }

  // One record: its fields, then filler spaces up to the layout's 120 characters, then a line feed.
  private static String record(String... fields) {
    return "%-120s\n".formatted(String.join("", fields));
  }

  // An alphanumeric field: left-justified, padded with spaces. The message never quotes the value,
  // which may be a PAN.
  private static String an(String value, int length) {
    if (value.length() > length) {
      throw new IllegalArgumentException(
          "A value of " + value.length() + " characters doesn't fit a field of " + length);
    }
    return ("%-" + length + "s").formatted(value);
  }

  // A numeric field: right-justified, padded with zeros, never negative.
  private static String n(long value, int length) {
    var digits = Long.toString(value);
    if (value < 0 || digits.length() > length) {
      throw new IllegalArgumentException(
          "A negative number, or one of more than " + length + " digits, doesn't fit its field");
    }
    return "0".repeat(length - digits.length()) + digits;
  }

  // Partly random, so it stays unique when the simulator restarts.
  private String newNetworkRecordId() {
    return "NR-" + randomId(12);
  }

  private String randomId(int length) {
    var id = new StringBuilder(length);
    for (var i = 0; i < length; i++) {
      id.append(ID_CHARACTERS.charAt(random.nextInt(ID_CHARACTERS.length())));
    }
    return id.toString();
  }

  /**
   * One Clearing as an Acquirer submits it: what the Issuer's file needs to carry. {@code toString}
   * leaves out the PAN.
   *
   * @param pan the card's PAN (DE 2)
   * @param amount the Charge's final amount (DE 4) and its currency (DE 49)
   * @param retrievalReferenceNumber the request ID of the Charge's Authorization (DE 37)
   * @param authorizationCode the code the Issuer gave when it approved it (DE 38)
   * @param acceptorId the Supplier's Acceptor ID (DE 42)
   * @param chargeDate the day of the Charge, in UTC
   */
  record Clearing(
      String pan,
      Money amount,
      String retrievalReferenceNumber,
      String authorizationCode,
      String acceptorId,
      LocalDate chargeDate) {

    @Override
    public String toString() {
      return "Clearing[retrievalReferenceNumber=%s, amount=%s]"
          .formatted(retrievalReferenceNumber, amount);
    }
  }

  /**
   * How the demo has one file depart from the Network's usual one, to show the Issuer's rule for a
   * Clearing edge case. Each changes the file's first Clearing, or adds a Clearing modelled on it
   * under a Network record ID and retrieval reference number of its own; the trailer stays true to
   * the records but for {@link #WRONG_TRAILER_TOTAL}.
   */
  enum EdgeCase {
    /** The first detail record appears again at the end, under the same Network record ID. */
    DUPLICATE_RECORD,
    /** The first Clearing is for $100.00 more than the Charge the Issuer authorized. */
    ABOVE_AUTHORIZED_AMOUNT,
    /** An extra Clearing on the first one's card, under an authorization code no approval has. */
    NO_AUTHORIZATION,
    /** An extra Clearing on a Luhn-valid PAN from the Issuer's range that no card has. */
    UNKNOWN_PAN,
    /** The trailer's amount total is one cent more than the records' sum. */
    WRONG_TRAILER_TOTAL,
  }

  /**
   * A Clearing File the Network wrote: its name in the drop folder and its content. {@code
   * toString} leaves out the content: it holds PANs.
   */
  record WrittenFile(String name, String content) {

    @Override
    public String toString() {
      return "WrittenFile[name=%s]".formatted(name);
    }
  }

  // A Clearing waiting for its file, with its Network record ID and its detail record. toString
  // leaves the record out: it holds the PAN.
  private record Unsent(String networkRecordId, Clearing clearing, String detailRecord) {

    // Writes the detail record now: a Clearing that doesn't fit the layout is refused here.
    static Unsent of(String networkRecordId, Clearing clearing) {
      return new Unsent(
          networkRecordId,
          clearing,
          record(
              "05",
              an(networkRecordId, 20),
              an(clearing.pan(), 19),
              n(clearing.amount().getMinorUnits(), 12),
              an(clearing.amount().getCurrency().getValue(), 3),
              an(clearing.retrievalReferenceNumber(), 12),
              an(clearing.authorizationCode(), 6),
              an(clearing.acceptorId(), 15),
              clearing.chargeDate().format(DATE)));
    }

    Money amount() {
      return clearing.amount();
    }

    LocalDate chargeDate() {
      return clearing.chargeDate();
    }

    // The same Clearing, under the same Network record ID, for more.
    Unsent raisedBy(long minorUnits) {
      var amount =
          new Money()
              .minorUnits(clearing.amount().getMinorUnits() + minorUnits)
              .currency(clearing.amount().getCurrency());
      return of(
          networkRecordId,
          new Clearing(
              clearing.pan(),
              amount,
              clearing.retrievalReferenceNumber(),
              clearing.authorizationCode(),
              clearing.acceptorId(),
              clearing.chargeDate()));
    }

    @Override
    public String toString() {
      return "Unsent[networkRecordId=%s, %s]".formatted(networkRecordId, clearing);
    }
  }
}
