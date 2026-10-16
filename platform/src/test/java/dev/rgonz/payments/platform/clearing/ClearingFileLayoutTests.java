package dev.rgonz.payments.platform.clearing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.payments.platform.shared.Money;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The Clearing File layout is a contract in {@code contracts} (clearing-file/layout.md): the
 * platform's reader parses the contract's sample file, which the simulator's writer is tested
 * against too.
 */
class ClearingFileLayoutTests {

  private static final Currency USD = Currency.getInstance("USD");

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

  // From the contracts module's jar, as the simulator reads it too.
  private static BufferedReader sample() {
    return new BufferedReader(
        new InputStreamReader(
            Objects.requireNonNull(
                ClearingFileLayoutTests.class.getResourceAsStream("/clearing-file/sample.txt")),
            StandardCharsets.US_ASCII));
  }
}
