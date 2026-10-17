package dev.rgonz.payments.loadtest;

import com.google.protobuf.Timestamp;
import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The cards file the simulator's {@code POST /demo/load-test-cards} writes, under the header {@code
 * pan,securityCode,expiry,amountMinorUnits,currency,acceptorId}, and the Authorize request that
 * charges one of its cards, as Globex's Acquirer sends it.
 */
final class Cards {

  // DE 18, as the simulator's Globex Acquirer sends it.
  private static final String MERCHANT_CATEGORY_CODE = "5085";
  private static final String REFERENCE_CHARACTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final SecureRandom random = new SecureRandom();
  private static final AtomicInteger traceNumbers = new AtomicInteger();

  private Cards() {}

  /**
   * Every card in the file, one record per line keyed by the header's names. Read here rather than
   * with Gatling's {@code csv()}, which works only inside a running simulation; no field holds a
   * comma or a quote.
   */
  static Queue<Map<String, Object>> read(Path file) throws IOException {
    var lines = Files.readAllLines(file);
    var names = lines.getFirst().split(",");
    var cards = new ConcurrentLinkedQueue<Map<String, Object>>();
    for (var line : lines.subList(1, lines.size())) {
      var values = line.split(",");
      var card = new HashMap<String, Object>();
      for (var i = 0; i < names.length; i++) {
        card.put(names[i], values[i]);
      }
      cards.add(card);
    }
    return cards;
  }

  /**
   * A Charge for the card's whole amount, under a new retrieval reference number; with a security
   * code that differs from the card's in every digit if {@code wrongSecurityCode}.
   */
  static AuthorizationRequest charge(Map<String, Object> card, boolean wrongSecurityCode) {
    var securityCode = (String) card.get("securityCode");
    var now = Instant.now();
    return AuthorizationRequest.newBuilder()
        .setPrimaryAccountNumber((String) card.get("pan"))
        .setAmountMinor(Long.parseLong((String) card.get("amountMinorUnits")))
        .setCurrencyCode((String) card.get("currency"))
        .setTransmittedAt(
            Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()))
        .setSystemTraceAuditNumber(
            "%06d".formatted(Math.floorMod(traceNumbers.getAndIncrement(), 999_999) + 1))
        .setExpirationDate((String) card.get("expiry"))
        .setMerchantCategoryCode(MERCHANT_CATEGORY_CODE)
        .setRetrievalReferenceNumber(newRetrievalReferenceNumber())
        .setCardAcceptorId((String) card.get("acceptorId"))
        .setCardAcceptorName("Globex")
        .setSecurityCode(wrongSecurityCode ? wrong(securityCode) : securityCode)
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
