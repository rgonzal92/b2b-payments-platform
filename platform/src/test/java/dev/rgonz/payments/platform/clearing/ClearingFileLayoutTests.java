package dev.rgonz.payments.platform.clearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import dev.rgonz.payments.platform.shared.Money;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The Clearing File layout is a contract in {@code contracts} (clearing-file/layout.md): the
 * platform's reader parses the contract's sample file, which the simulator's writer is tested
 * against too.
 */
class ClearingFileLayoutTests {

  private static final Currency USD = Currency.getInstance("USD");
  // The first detail record's, in the sample.
  private static final String SAMPLE_PAN = "9900000000000010";

  @Test
  void theSampleFileReadsAsItsHeaderItsClearingsAndItsTrailer() throws IOException {
    var clearings = new ArrayList<ClearingRecord>();

    ClearingFileLayout.Envelope envelope;
    try (var sample = sample()) {
      envelope = ClearingFileLayout.read(sample, clearings::add);
    }

    assertThat(envelope.header())
        .isEqualTo(new FileHeader("NET-20261015-0001", 42, LocalDate.of(2026, 10, 15)));
    assertThat(clearings)
        .containsExactly(
            new ClearingRecord(
                "NR0000000001",
                "9900000000000010",
                new Money(1_240_000, USD),
                "A1B2C3D4E5F6",
                "X7Y8Z9",
                "GLOBEX000000001",
                LocalDate.of(2026, 10, 14)),
            new ClearingRecord(
                "NR0000000002",
                "9900000000000028",
                new Money(500_000, USD),
                "G7H8J9K0L1M2",
                "Q4W5E6",
                "GLOBEX000000001",
                LocalDate.of(2026, 10, 14)),
            new ClearingRecord(
                "NR0000000003",
                "9900000000000036",
                new Money(12_345, USD),
                "N3P4R5S6T7U8",
                "A1S2D3",
                "INITECH00000042",
                LocalDate.of(2026, 10, 15)));
    assertThat(envelope.trailer())
        .isEqualTo(new FileTrailer(3, new Money(1_752_345, USD), new Money(1_752_345, USD)));
  }

  // Each error names the line and the field, never what the field holds: the detail record's PAN
  // would otherwise reach the logs and the job repository.
  @ParameterizedTest(name = "{0}")
  @MethodSource("malformedFiles")
  void aRecordOffTheLayoutIsRefusedByItsLineAndFieldAndNeverEchoesThePan(
      String what, List<String> lines, String expectedMessage) {
    assertThatExceptionOfType(MalformedClearingFileException.class)
        .isThrownBy(
            () ->
                ClearingFileLayout.read(
                    new BufferedReader(new StringReader(String.join("\n", lines))), clearing -> {}))
        .withMessage(expectedMessage)
        .withMessageNotContaining(SAMPLE_PAN);
  }

  static Stream<Arguments> malformedFiles() throws IOException {
    var lines = sampleLines();
    var header = lines.getFirst();
    var detail = lines.get(1);
    var trailer = lines.getLast();
    return Stream.of(
        arguments(
            "a short record",
            List.of(header, detail.substring(0, 119), trailer),
            "Line 2: the record is 119 characters long, not 120."),
        arguments(
            "an unknown record type",
            List.of(header, "06" + detail.substring(2), trailer),
            "Line 2: the record type isn't 90, 05 or 92."),
        arguments(
            "a PAN that's too short",
            List.of(header, replace(detail, 23, "99000000000        "), trailer),
            "Line 2: the PAN (positions 23–41) isn't 12 to 19 digits."),
        arguments(
            "an amount that isn't a number",
            List.of(header, replace(detail, 42, "00000124000X"), trailer),
            "Line 2: the amount (positions 42–53) isn't a number."),
        arguments(
            "a currency that isn't ISO 4217",
            List.of(header, replace(detail, 54, "XXQ"), trailer),
            "Line 2: the currency (positions 54–56) isn't an ISO 4217 code."),
        arguments(
            "a Charge date that isn't a date",
            List.of(header, replace(detail, 90, "20261332"), trailer),
            "Line 2: the Charge date (positions 90–97) isn't a date (YYYYMMDD)."),
        arguments(
            "a blank Network record ID",
            List.of(header, replace(detail, 3, " ".repeat(20)), trailer),
            "Line 2: the Network record ID (positions 3–22) is blank."),
        arguments(
            "filler that isn't spaces",
            List.of(header, replace(detail, 120, "X"), trailer),
            "Line 2: the filler (positions 98–120) isn't spaces."),
        arguments(
            "no header", List.of(detail, trailer), "Line 1: the first record isn't a header (90)."),
        arguments(
            "a second header",
            List.of(header, detail, header, trailer),
            "Line 3: a second header."),
        arguments(
            "no trailer", List.of(header, detail), "Line 2: the file ends without a trailer (92)."),
        arguments(
            "a record after the trailer",
            List.of(header, trailer, detail),
            "Line 3: a record follows the trailer."),
        arguments("an empty file", List.of(), "Line 1: the file is empty."));
  }

  // The record with the text at its 1-based position, as layout.md counts.
  private static String replace(String record, int position, String text) {
    return record.substring(0, position - 1)
        + text
        + record.substring(position - 1 + text.length());
  }

  private static List<String> sampleLines() throws IOException {
    try (var sample = sample()) {
      return sample.lines().toList();
    }
  }

  // From the contracts module's jar, as the simulator reads it too.
  private static BufferedReader sample() {
    return new BufferedReader(
        new InputStreamReader(
            Objects.requireNonNull(
                ClearingFileLayoutTests.class.getResourceAsStream("/clearing-file/sample.txt")),
            StandardCharsets.US_ASCII));
  }
}
