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
    var detailRecord =
        record(
            "05",
            an("NR-" + randomId(12), 20),
            an(clearing.pan(), 19),
            n(clearing.amount().getMinorUnits(), 12),
            an(clearing.amount().getCurrency().getValue(), 3),
            an(clearing.retrievalReferenceNumber(), 12),
            an(clearing.authorizationCode(), 6),
            an(clearing.acceptorId(), 15),
            clearing.chargeDate().format(DATE));
    unsent.add(new Unsent(detailRecord, clearing.amount(), clearing.chargeDate()));
  }

  /**
   * Writes the Clearing File for this Business Date with every Clearing not yet sent, and returns
   * its name in the drop folder. A Clearing dated after the Business Date waits for a later file.
   * Writes nothing and returns empty when there is nothing to send.
   */
  synchronized Optional<String> write(LocalDate businessDate) {
    var sending =
        unsent.stream().filter(clearing -> !clearing.chargeDate().isAfter(businessDate)).toList();
    if (sending.isEmpty()) {
      return Optional.empty();
    }
    var fileId = "NET-" + businessDate.format(DATE) + "-" + randomId(7);
    // One currency, USD, the only one the contract's Money has.
    var currency = sending.getFirst().amount().getCurrency().getValue();
    var total = sending.stream().mapToLong(clearing -> clearing.amount().getMinorUnits()).sum();
    var file = new StringBuilder();
    file.append(record("90", an(fileId, 20), n(sequenceNumber + 1, 8), businessDate.format(DATE)));
    sending.forEach(clearing -> file.append(clearing.detailRecord()));
    file.append(record("92", n(sending.size(), 8), n(total, 15), n(total, 15), an(currency, 3)));
    var fileName = fileId + ".txt";
    // Written under a name starting with '.', which the platform ignores, then renamed in one step:
    // the platform never reads half a file (the layout's delivery rule).
    var partial = dropFolder.resolve("." + fileName);
    try {
      Files.createDirectories(dropFolder);
      Files.writeString(partial, file, StandardCharsets.US_ASCII);
      Files.move(partial, dropFolder.resolve(fileName), StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      deleteHalfWritten(partial, e);
      throw new UncheckedIOException(e);
    }
    sequenceNumber++;
    unsent.removeAll(sending);
    log.info(
        "The Network wrote Clearing File {} for {}: {} Clearings, {} {} minor units",
        fileName,
        businessDate,
        sending.size(),
        currency,
        total);
    return Optional.of(fileName);
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

  // A Clearing waiting for its file, as its detail record. toString leaves the record out: it holds
  // the PAN.
  private record Unsent(String detailRecord, Money amount, LocalDate chargeDate) {

    @Override
    public String toString() {
      return "Unsent[amount=%s, chargeDate=%s]".formatted(amount, chargeDate);
    }
  }
}
