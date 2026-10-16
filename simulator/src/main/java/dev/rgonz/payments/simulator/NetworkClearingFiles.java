package dev.rgonz.payments.simulator;

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
  private final List<Clearing> unsent = new ArrayList<>();
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

  /** Takes a Clearing an Acquirer submitted, for the next file. */
  synchronized void submit(Clearing clearing) {
    unsent.add(clearing);
  }

  /**
   * Writes the Clearing File for this Business Date with every Clearing not yet sent, and returns
   * its name in the drop folder; writes nothing and returns empty when there is nothing to send.
   */
  synchronized Optional<String> write(LocalDate businessDate) {
    if (unsent.isEmpty()) {
      return Optional.empty();
    }
    var fileId = "NET-" + businessDate.format(DATE) + "-" + randomId(7);
    var currency = unsent.getFirst().currency();
    var total = unsent.stream().mapToLong(Clearing::amountMinor).sum();
    var lines = new StringBuilder();
    line(lines, "90", an(fileId, 20), n(sequenceNumber + 1, 8), businessDate.format(DATE));
    for (var clearing : unsent) {
      line(
          lines,
          "05",
          an("NR-" + randomId(12), 20),
          an(clearing.pan(), 19),
          n(clearing.amountMinor(), 12),
          an(clearing.currency(), 3),
          an(clearing.retrievalReferenceNumber(), 12),
          an(clearing.authorizationCode(), 6),
          an(clearing.acceptorId(), 15),
          clearing.chargeDate().format(DATE));
    }
    line(lines, "92", n(unsent.size(), 8), n(total, 15), n(total, 15), an(currency, 3));
    var fileName = fileId + ".txt";
    // Written under a name starting with '.', which the platform ignores, then renamed in one step:
    // the platform never reads half a file (the layout's delivery rule).
    var partial = dropFolder.resolve("." + fileName);
    try {
      Files.createDirectories(dropFolder);
      Files.writeString(partial, lines, StandardCharsets.US_ASCII);
      Files.move(partial, dropFolder.resolve(fileName), StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    sequenceNumber++;
    log.info(
        "The Network wrote Clearing File {} for {}: {} Clearings, {} {} minor units",
        fileName,
        businessDate,
        unsent.size(),
        currency,
        total);
    unsent.clear();
    return Optional.of(fileName);
  }

  // One record: its fields, then filler spaces up to the layout's 120 characters.
  private static void line(StringBuilder lines, String... fields) {
    lines.append("%-120s".formatted(String.join("", fields))).append('\n');
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

  // A numeric field: right-justified, padded with zeros.
  private static String n(long value, int length) {
    return ("%0" + length + "d").formatted(value);
  }

  private String randomId(int length) {
    var id = new StringBuilder(length);
    for (var i = 0; i < length; i++) {
      id.append(ID_CHARACTERS.charAt(random.nextInt(ID_CHARACTERS.length())));
    }
    return id.toString();
  }

  /**
   * One Clearing as an Acquirer submits it: what the Issuer's file needs to carry. Held in memory
   * only, until the file is written; {@code toString} leaves out the PAN.
   *
   * @param pan the card's PAN (DE 2)
   * @param amountMinor the Charge's final amount in minor units (DE 4)
   * @param currency its ISO 4217 alphabetic code (DE 49)
   * @param retrievalReferenceNumber the request ID of the Charge's Authorization (DE 37)
   * @param authorizationCode the code the Issuer gave when it approved it (DE 38)
   * @param acceptorId the Supplier's Acceptor ID (DE 42)
   * @param chargeDate the day of the Charge, in UTC
   */
  record Clearing(
      String pan,
      long amountMinor,
      String currency,
      String retrievalReferenceNumber,
      String authorizationCode,
      String acceptorId,
      LocalDate chargeDate) {

    @Override
    public String toString() {
      return "Clearing[retrievalReferenceNumber=%s, amountMinor=%d, currency=%s]"
          .formatted(retrievalReferenceNumber, amountMinor, currency);
    }
  }
}
