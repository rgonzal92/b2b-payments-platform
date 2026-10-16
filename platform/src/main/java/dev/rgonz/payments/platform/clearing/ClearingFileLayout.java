package dev.rgonz.payments.platform.clearing;

import dev.rgonz.payments.platform.shared.Money;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Currency;
import java.util.HashMap;
import java.util.function.Consumer;

/**
 * The Clearing File's fixed-width layout, the contract in {@code contracts}' {@code
 * clearing-file/layout.md}: one 120-character record per line, a header, the Clearings, then a
 * trailer. A detail record holds a PAN, so an error names the line and the field, never what the
 * field holds (ADR-0004).
 */
final class ClearingFileLayout {

  static final int RECORD_LENGTH = 120;

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

  private ClearingFileLayout() {}

  /** Opens the file for reading, in the layout's character set (US-ASCII). */
  static BufferedReader open(Path file) throws IOException {
    return Files.newBufferedReader(file, StandardCharsets.US_ASCII);
  }

  /**
   * Reads a whole file in one pass, handing each Clearing to {@code eachClearing} in file order,
   * and returns its header and trailer. Throws {@link MalformedClearingFileException} when a record
   * doesn't follow the layout, the records aren't one header, the Clearings, then one trailer, or
   * the trailer's control totals aren't its Clearings'.
   */
  static Envelope read(BufferedReader lines, Consumer<ClearingRecord> eachClearing)
      throws IOException {
    var header = readHeader(lines);
    FileTrailer trailer = null;
    var clearings = 0L;
    // Each currency's total: the trailer's amount total is in one currency, which comes last.
    var amountTotals = new HashMap<Currency, Long>();
    var lineNumber = 1;
    String line;
    while ((line = lines.readLine()) != null) {
      lineNumber++;
      if (trailer != null) {
        throw new MalformedClearingFileException(lineNumber, "a record follows the trailer");
      }
      switch (parse(line, lineNumber)) {
        case ClearingRecord clearing -> {
          clearings++;
          amountTotals.merge(
              clearing.amount().currency(), clearing.amount().minorUnits(), Long::sum);
          eachClearing.accept(clearing);
        }
        case FileTrailer fileTrailer -> trailer = fileTrailer;
        case FileHeader _ ->
            throw new MalformedClearingFileException(lineNumber, "a second header");
      }
    }
    if (trailer == null) {
      throw new MalformedClearingFileException(lineNumber, "the file ends without a trailer (92)");
    }
    if (trailer.recordCount() != clearings) {
      throw new MalformedClearingFileException(
          lineNumber, "the record count (positions 3–10) isn't the number of detail records");
    }
    var amountTotal = trailer.amountTotal();
    if (!amountTotals.keySet().stream().allMatch(amountTotal.currency()::equals)
        || amountTotals.getOrDefault(amountTotal.currency(), 0L) != amountTotal.minorUnits()) {
      throw new MalformedClearingFileException(
          lineNumber,
          "the amount total (positions 11–25) isn't the sum of the detail records' amounts");
    }
    return new Envelope(header, trailer);
  }

  /** Reads the file's first line, which must be its header. */
  static FileHeader readHeader(BufferedReader lines) throws IOException {
    var line = lines.readLine();
    if (line == null) {
      throw new MalformedClearingFileException(1, "the file is empty");
    }
    if (parse(line, 1) instanceof FileHeader header) {
      return header;
    }
    throw new MalformedClearingFileException(1, "the first record isn't a header (90)");
  }

  /** One line of the file as its record. */
  static Record parse(String line, int lineNumber) {
    if (line.length() != RECORD_LENGTH) {
      throw new MalformedClearingFileException(
          lineNumber, "the record is " + line.length() + " characters long, not " + RECORD_LENGTH);
    }
    var fields = new Fields(line, lineNumber);
    return switch (line.substring(0, 2)) {
      case "90" -> {
        fields.filler(39);
        yield new FileHeader(
            fields.alphanumeric(3, 22, "file ID"),
            fields.number(23, 30, "sequence number"),
            fields.date(31, 38, "Business Date"));
      }
      case "05" -> {
        fields.filler(98);
        yield new ClearingRecord(
            fields.alphanumeric(3, 22, "Network record ID"),
            fields.pan(23, 41),
            new Money(fields.number(42, 53, "amount"), fields.currency(54, 56)),
            fields.alphanumeric(57, 68, "retrieval reference number"),
            fields.alphanumeric(69, 74, "authorization code"),
            fields.alphanumeric(75, 89, "Acceptor ID"),
            fields.date(90, 97, "Charge date"));
      }
      case "92" -> {
        fields.filler(44);
        var currency = fields.currency(41, 43);
        yield new FileTrailer(
            fields.number(3, 10, "record count"),
            new Money(fields.number(11, 25, "amount total"), currency),
            new Money(fields.number(26, 40, "net settlement amount"), currency));
      }
      default ->
          throw new MalformedClearingFileException(
              lineNumber, "the record type isn't 90, 05 or 92");
    };
  }

  /** The file's records: its header, a Clearing, or its trailer. */
  sealed interface Record permits FileHeader, ClearingRecord, FileTrailer {}

  /** What wraps a file's Clearings: its header and its trailer. */
  record Envelope(FileHeader header, FileTrailer trailer) {}

  // A record's fields, by the 1-based positions in layout.md.
  private record Fields(String line, int lineNumber) {

    String alphanumeric(int from, int to, String name) {
      var value = field(from, to).stripTrailing();
      if (value.isEmpty()) {
        throw malformed(from, to, name, "is blank");
      }
      return value;
    }

    long number(int from, int to, String name) {
      var value = field(from, to);
      if (!digitsOnly(value)) {
        throw malformed(from, to, name, "isn't a number");
      }
      return Long.parseLong(value);
    }

    LocalDate date(int from, int to, String name) {
      try {
        return LocalDate.parse(field(from, to), DATE);
      } catch (DateTimeParseException notADate) {
        throw malformed(from, to, name, "isn't a date (YYYYMMDD)");
      }
    }

    Currency currency(int from, int to) {
      try {
        return Currency.getInstance(field(from, to));
      } catch (IllegalArgumentException unknown) {
        throw malformed(from, to, "currency", "isn't an ISO 4217 code");
      }
    }

    String pan(int from, int to) {
      var pan = field(from, to).stripTrailing();
      if (pan.length() < 12 || !digitsOnly(pan)) {
        throw malformed(from, to, "PAN", "isn't 12 to 19 digits");
      }
      return pan;
    }

    void filler(int from) {
      if (!field(from, RECORD_LENGTH).isBlank()) {
        throw malformed(from, RECORD_LENGTH, "filler", "isn't spaces");
      }
    }

    private String field(int from, int to) {
      return line.substring(from - 1, to);
    }

    private MalformedClearingFileException malformed(
        int from, int to, String name, String problem) {
      return new MalformedClearingFileException(
          lineNumber, "the " + name + " (positions " + from + "–" + to + ") " + problem);
    }

    private static boolean digitsOnly(String value) {
      return !value.isEmpty() && value.chars().allMatch(c -> c >= '0' && c <= '9');
    }
  }
}
