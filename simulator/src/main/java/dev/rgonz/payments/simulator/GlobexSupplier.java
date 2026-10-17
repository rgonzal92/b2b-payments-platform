package dev.rgonz.payments.simulator;

import dev.rgonz.payments.contracts.client.model.InvoiceSupplier;
import dev.rgonz.payments.contracts.client.model.Money;
import dev.rgonz.payments.contracts.client.model.RemittanceAdvice;
import dev.rgonz.payments.contracts.client.model.SingleUseCardDetails;
import dev.rgonz.payments.simulator.GlobexAcquirer.Authorization;
import io.micrometer.observation.ObservationRegistry;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Globex, the Supplier on Acme's demo Invoices: receives Remittance Advice at its remittance
 * address, opens each card link once, keeps the card's details in memory, and charges the card
 * through its Acquirer. Logs only a card's last four digits, never its PAN, security code or link.
 */
@RestController
class GlobexSupplier {

  /** Globex's remittance address on the simulator. */
  static final String ADVICE_PATH = "/globex/remittance-advice";

  private static final Logger log = LoggerFactory.getLogger(GlobexSupplier.class);

  private final RestClient platform;
  private final GlobexAcquirer acquirer;
  private final Set<String> receivedAdvice = ConcurrentHashMap.newKeySet();
  private final Map<UUID, ReceivedCard> cardsByInvoice = new ConcurrentHashMap<>();

  // Untraced: a trace would record each request's URL, and here the URL is the card link.
  GlobexSupplier(RestClient.Builder platform, GlobexAcquirer acquirer) {
    this.platform = platform.observationRegistry(ObservationRegistry.NOOP).build();
    this.acquirer = acquirer;
  }

  /**
   * The platform delivers each advice at least once, under the same Idempotency-Key each time:
   * Globex keeps the first and drops the rest. Any advice received answers 200, even when its link
   * won't open, since sending the same link again wouldn't help.
   */
  @PostMapping(ADVICE_PATH)
  ResponseEntity<Void> receive(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody RemittanceAdvice advice) {
    if (!receivedAdvice.add(idempotencyKey)) {
      log.info(
          "Globex dropped a repeat of the Remittance Advice for {}", advice.getInvoiceNumber());
      return ResponseEntity.ok().build();
    }
    openCardLink(advice);
    return ResponseEntity.ok().build();
  }

  /**
   * Globex as a Buyer's ERP names it on an Invoice: where it receives Remittance Advice, and the
   * Acceptor ID the Network will carry on its Charges.
   */
  static InvoiceSupplier onInvoices(String remittanceAddress, String acceptorId) {
    return new InvoiceSupplier()
        .name("Globex")
        .taxId("12-3456789")
        .buyerReference("GLOBEX-01")
        .remittanceAddress(remittanceAddress)
        .acceptorId(acceptorId);
  }

  /** The card Globex received for the Invoice, if it has one. */
  Optional<ReceivedCard> cardFor(UUID invoiceId) {
    return Optional.ofNullable(cardsByInvoice.get(invoiceId));
  }

  /**
   * Charges the card Globex received for the Invoice, for its full amount, through Globex's
   * Acquirer; empty when Globex has no card for the Invoice.
   */
  Optional<Authorization> charge(UUID invoiceId) {
    return cardFor(invoiceId).map(acquirer::charge);
  }

  // A plain GET with no sign-in: the link is the credential. The link and the error's message stay
  // out of the logs, since an I/O error's message quotes the URL.
  private void openCardLink(RemittanceAdvice advice) {
    SingleUseCardDetails card;
    try {
      card = platform.get().uri(advice.getCardLink()).retrieve().body(SingleUseCardDetails.class);
    } catch (RestClientResponseException refused) {
      log.warn(
          "Globex couldn't open the card link for {}: the platform answered {}",
          advice.getInvoiceNumber(),
          refused.getStatusCode().value());
      return;
    } catch (RestClientException failed) {
      log.warn(
          "Globex couldn't open the card link for {}: {}",
          advice.getInvoiceNumber(),
          failed.getClass().getSimpleName());
      return;
    }
    Objects.requireNonNull(card, "the platform's card details");
    var received =
        new ReceivedCard(
            advice.getInvoiceNumber(),
            card.getPan(),
            card.getSecurityCode(),
            card.getExpiresAt(),
            card.getAmount());
    cardsByInvoice.put(advice.getInvoiceId(), received);
    log.info(
        "Globex received the card ending {} for {}, {} {} minor units",
        received.lastFour(),
        advice.getInvoiceNumber(),
        card.getAmount().getCurrency(),
        card.getAmount().getMinorUnits());
  }

  /**
   * A card Globex received through its link, with what it needs to charge it, kept in memory only.
   * {@code toString} leaves out the PAN and security code.
   */
  record ReceivedCard(
      String invoiceNumber,
      String pan,
      String securityCode,
      OffsetDateTime expiresAt,
      Money amount) {

    // The card's expiry month as an Authorization request carries it (DE 14), in UTC like the
    // platform's.
    private static final DateTimeFormatter EXPIRY_MONTH = DateTimeFormatter.ofPattern("yyMM");

    String lastFour() {
      return pan.substring(pan.length() - 4);
    }

    /** The card's expiry month, {@code YYMM} in UTC. */
    String expiryMonth() {
      return expiresAt.withOffsetSameInstant(ZoneOffset.UTC).format(EXPIRY_MONTH);
    }

    @Override
    public String toString() {
      return "ReceivedCard[invoiceNumber=%s, lastFour=%s, expiresAt=%s, amount=%s]"
          .formatted(invoiceNumber, lastFour(), expiresAt, amount);
    }
  }
}
