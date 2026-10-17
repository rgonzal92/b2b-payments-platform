package dev.rgonz.payments.loadtest;

import com.google.protobuf.Timestamp;
import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The cards file the simulator's {@code POST /demo/load-test-cards} writes, under the header {@code
 * pan,securityCode,expiry,amountMinorUnits,currency,acceptorId}, and the Authorize request that
 * charges one of its cards, as Globex's Acquirer sends it.
 */
final class CardsFile {

  /** One card as Globex received it: its expiry as {@code YYMM}, its amount in minor units. */
  record Card(
      String pan,
      String securityCode,
      String expiry,
      long amountMinorUnits,
      String currency,
      String acceptorId) {}

  // DE 18, as the simulator's Globex Acquirer sends it.
  private static final String MERCHANT_CATEGORY_CODE = "5085";
  private static final String REFERENCE_CHARACTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final SecureRandom random = new SecureRandom();
  private static final AtomicInteger traceNumbers = new AtomicInteger();

  private CardsFile() {}

  /**
   * Every card in the file, in its order. Read here rather than with Gatling's {@code csv()}, which
   * works only inside a running simulation; no field holds a comma or a quote, and the columns are
   * in the header's order.
   */
  static Queue<Card> read(Path file) throws IOException {
    var lines = Files.readAllLines(file);
    var cards = new ConcurrentLinkedQueue<Card>();
    for (var line : lines.subList(1, lines.size())) {
      var field = line.split(",");
      cards.add(
          new Card(field[0], field[1], field[2], Long.parseLong(field[3]), field[4], field[5]));
    }
    return cards;
  }

  /**
   * A Charge for the card's whole amount, under a new retrieval reference number; with a security
   * code that differs from the card's in every digit if {@code wrongSecurityCode}.
   */
  static AuthorizationRequest charge(Card card, boolean wrongSecurityCode) {
    var now = Instant.now();
    return AuthorizationRequest.newBuilder()
        .setPrimaryAccountNumber(card.pan())
        .setAmountMinor(card.amountMinorUnits())
        .setCurrencyCode(card.currency())
        .setTransmittedAt(
            Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()))
        .setSystemTraceAuditNumber(
            "%06d".formatted(Math.floorMod(traceNumbers.getAndIncrement(), 999_999) + 1))
        .setExpirationDate(card.expiry())
        .setMerchantCategoryCode(MERCHANT_CATEGORY_CODE)
        .setRetrievalReferenceNumber(newRetrievalReferenceNumber())
        .setCardAcceptorId(card.acceptorId())
        .setCardAcceptorName("Globex")
        .setSecurityCode(wrongSecurityCode ? wrong(card.securityCode()) : card.securityCode())
        .build();
  }

  // Each digit plus one, 9 becoming 0.
  private static String wrong(String securityCode) {
    var wrong = new StringBuilder(securityCode.length());
    securityCode.chars().forEach(digit -> wrong.append((char) ('0' + (digit - '0' + 1) % 10)));
    return wrong.toString();
  }

  // DE 37: 12 random letters and digits, as the simulator makes them, so they never repeat.
  private static String newRetrievalReferenceNumber() {
    var reference = new StringBuilder(12);
    for (var i = 0; i < 12; i++) {
      reference.append(REFERENCE_CHARACTERS.charAt(random.nextInt(REFERENCE_CHARACTERS.length())));
    }
    return reference.toString();
  }
}
