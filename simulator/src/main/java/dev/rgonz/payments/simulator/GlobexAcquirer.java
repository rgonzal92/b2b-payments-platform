package dev.rgonz.payments.simulator;

import com.google.protobuf.Timestamp;
import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.contracts.network.AuthorizationResponse;
import dev.rgonz.payments.simulator.GlobexSupplier.ReceivedCard;
import dev.rgonz.payments.simulator.NetworkClearingFiles.Clearing;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Globex's Acquirer: turns Globex's Charge into an Authorization request, under the Acceptor ID it
 * gave Globex, and sends it through the Network. Logs only the card's last four digits, never its
 * PAN or security code.
 */
@Component
class GlobexAcquirer {

  // DE 18: the category of Globex's business, made up: industrial supplies.
  private static final String MERCHANT_CATEGORY_CODE = "5085";
  private static final String REFERENCE_CHARACTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final Logger log = LoggerFactory.getLogger(GlobexAcquirer.class);

  private final Network network;
  private final NetworkClearingFiles clearingFiles;
  private final String acceptorId;
  private final SecureRandom random = new SecureRandom();
  private final AtomicInteger traceNumbers = new AtomicInteger();

  GlobexAcquirer(
      Network network,
      NetworkClearingFiles clearingFiles,
      @Value("${simulator.globex.acceptor-id}") String acceptorId) {
    this.network = network;
    this.clearingFiles = clearingFiles;
    this.acceptorId = acceptorId;
  }

  /**
   * Charges the card for its full amount and returns the Issuer's answer. An approved Charge is
   * submitted for Clearing at once, so it goes to the Issuer in the Network's next Clearing File; a
   * declined one, the Network's own {@code 91} on a Charge it reversed included, never is.
   */
  Authorization charge(ReceivedCard card) {
    var now = Instant.now();
    var request =
        AuthorizationRequest.newBuilder()
            .setPrimaryAccountNumber(card.pan())
            .setAmountMinor(card.amount().getMinorUnits())
            .setCurrencyCode(card.amount().getCurrency().getValue())
            .setTransmittedAt(
                Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()))
            .setSystemTraceAuditNumber(nextTraceNumber())
            .setExpirationDate(card.expiryMonth())
            .setMerchantCategoryCode(MERCHANT_CATEGORY_CODE)
            .setRetrievalReferenceNumber(newRetrievalReferenceNumber())
            .setCardAcceptorId(acceptorId)
            .setCardAcceptorName("Globex")
            .setSecurityCode(card.securityCode())
            .build();
    var authorization = Authorization.of(network.authorize(request));
    if (authorization.approved()) {
      clearingFiles.submit(
          new Clearing(
              card.pan(),
              card.amount(),
              request.getRetrievalReferenceNumber(),
              authorization.authorizationCode(),
              acceptorId,
              LocalDate.ofInstant(now, ZoneOffset.UTC)));
    }
    log.info(
        "Globex's Charge {} on the card ending {} for {}: response code {}",
        request.getRetrievalReferenceNumber(),
        card.lastFour(),
        card.invoiceNumber(),
        authorization.responseCode());
    return authorization;
  }

  // DE 11: counts this Acquirer's messages from 000001, starting over after 999999.
  private String nextTraceNumber() {
    return "%06d".formatted(Math.floorMod(traceNumbers.getAndIncrement(), 999_999) + 1);
  }

  // DE 37, the request ID the platform keys its Authorizations by: 12 random letters and digits, so
  // it stays unique across restarts of the simulator, when the trace numbers start over.
  private String newRetrievalReferenceNumber() {
    var reference = new StringBuilder(12);
    for (var i = 0; i < 12; i++) {
      reference.append(REFERENCE_CHARACTERS.charAt(random.nextInt(REFERENCE_CHARACTERS.length())));
    }
    return reference.toString();
  }

  /**
   * The Issuer's answer to a Charge: approved with response code {@code 00} and an authorization
   * code, or declined with another response code and no authorization code.
   */
  record Authorization(boolean approved, String responseCode, String authorizationCode) {

    static Authorization of(AuthorizationResponse response) {
      var code = response.getAuthorizationCode();
      return new Authorization(
          "00".equals(response.getResponseCode()),
          response.getResponseCode(),
          code.isEmpty() ? null : code);
    }
  }
}
