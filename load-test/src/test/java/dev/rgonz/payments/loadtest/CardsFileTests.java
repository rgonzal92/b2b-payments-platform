package dev.rgonz.payments.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The simulation reads the cards file the simulator writes and charges each card through it. */
class CardsFileTests {

  @TempDir Path folder;

  @Test
  void eachLineOfTheCardsFileBecomesAnAuthorizeRequestForTheWholeAmount() throws IOException {
    var file = cardsFile("9900001234567890,321,2611,1000,USD,GLOBEX000000001");

    var cards = CardsFile.read(file);
    var request = CardsFile.charge(cards.poll(), false);

    assertThat(cards).isEmpty();
    assertThat(request.getPrimaryAccountNumber()).isEqualTo("9900001234567890");
    assertThat(request.getSecurityCode()).isEqualTo("321");
    assertThat(request.getExpirationDate()).isEqualTo("2611");
    assertThat(request.getAmountMinor()).isEqualTo(1_000);
    assertThat(request.getCurrencyCode()).isEqualTo("USD");
    assertThat(request.getCardAcceptorId()).isEqualTo("GLOBEX000000001");
    assertThat(request.getRetrievalReferenceNumber()).matches("[0-9A-Z]{12}");
    assertThat(request.getSystemTraceAuditNumber()).matches("\\d{6}");
    assertThat(request.hasTransmittedAt()).isTrue();
  }

  @Test
  void aWrongSecurityCodeDiffersInEveryDigit() throws IOException {
    var file = cardsFile("9900001234567890,309,2611,1000,USD,GLOBEX000000001");

    var request = CardsFile.charge(CardsFile.read(file).poll(), true);

    assertThat(request.getSecurityCode()).isEqualTo("410");
  }

  @Test
  void eachChargeHasItsOwnRetrievalReferenceNumber() throws IOException {
    var card =
        CardsFile.read(cardsFile("9900001234567890,321,2611,1000,USD,GLOBEX000000001")).poll();

    assertThat(CardsFile.charge(card, false).getRetrievalReferenceNumber())
        .isNotEqualTo(CardsFile.charge(card, false).getRetrievalReferenceNumber());
  }

  // The header LoadTestCards.HEADER writes in the simulator.
  private Path cardsFile(String line) throws IOException {
    return Files.writeString(
        folder.resolve("cards.csv"),
        "pan,securityCode,expiry,amountMinorUnits,currency,acceptorId\n" + line + "\n");
  }
}
