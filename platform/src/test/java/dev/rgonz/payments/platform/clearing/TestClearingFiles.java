package dev.rgonz.payments.platform.clearing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Writes Clearing Files in the layout of {@code contracts}' {@code clearing-file/layout.md}, for
 * tests that drop them in the platform's drop folder. Public for tests outside {@code clearing}.
 */
public final class TestClearingFiles {

  private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
  private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 15);

  private TestClearingFiles() {}

  /** One detail record's fields, in USD. */
  public record Detail(
      String networkRecordId,
      String pan,
      long amountMinor,
      String retrievalReferenceNumber,
      String authorizationCode,
      String acceptorId) {}

  /**
   * Writes a file of these Clearings, with its header (a new file ID, the sequence number, Business
   * Date 2026-10-15) and a trailer that totals them, into the folder, and returns its name.
   */
  public static String drop(Path folder, String fileId, long sequenceNumber, List<Detail> details) {
    var lines = new ArrayList<String>();
    lines.add(header(fileId, sequenceNumber));
    details.forEach(detail -> lines.add(detail(detail)));
    lines.add(trailer(details));
    return write(folder, lines);
  }

  /** Writes these lines as a file into the folder, and returns its name. */
  public static String write(Path folder, List<String> lines) {
    var name = "clearing-" + UUID.randomUUID() + ".txt";
    try {
      Files.createDirectories(folder);
      Files.write(folder.resolve(name), lines, StandardCharsets.US_ASCII);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return name;
  }

  public static String header(String fileId, long sequenceNumber) {
    return record("90%-20s%08d%s".formatted(fileId, sequenceNumber, DATE.format(BUSINESS_DATE)));
  }

  public static String detail(Detail detail) {
    return record(
        "05%-20s%-19s%012dUSD%-12s%-6s%-15s%s"
            .formatted(
                detail.networkRecordId(),
                detail.pan(),
                detail.amountMinor(),
                detail.retrievalReferenceNumber(),
                detail.authorizationCode(),
                detail.acceptorId(),
                DATE.format(BUSINESS_DATE.minusDays(1))));
  }

  /** A trailer that totals the Clearings, whose net settlement amount is their total. */
  public static String trailer(List<Detail> details) {
    return trailer(details, total(details));
  }

  /** A trailer that totals the Clearings, with the Network's own net settlement amount. */
  public static String trailer(List<Detail> details, long netSettlementMinor) {
    return record(
        "92%08d%015d%015dUSD".formatted(details.size(), total(details), netSettlementMinor));
  }

  private static long total(List<Detail> details) {
    return details.stream().mapToLong(Detail::amountMinor).sum();
  }

  /** A new ID of letters and digits, as the Network gives files, records and requests. */
  public static String newId(String prefix, int length) {
    var random = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    return (prefix + random).substring(0, length);
  }

  private static String record(String fields) {
    var record = fields + " ".repeat(ClearingFileLayout.RECORD_LENGTH - fields.length());
    assertThat(record).hasSize(ClearingFileLayout.RECORD_LENGTH);
    return record;
  }
}
