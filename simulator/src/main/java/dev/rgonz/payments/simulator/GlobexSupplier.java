package dev.rgonz.payments.simulator;

import dev.rgonz.payments.contracts.client.model.Money;
import dev.rgonz.payments.contracts.client.model.RemittanceAdvice;
import dev.rgonz.payments.contracts.client.model.SingleUseCardDetails;
import java.time.OffsetDateTime;
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
 * address and opens each card link once. Logs only a card's last four digits, never its PAN,
 * security code or link.
 */
@RestController
class GlobexSupplier {

  /** Globex's remittance address on the simulator. */
  static final String ADVICE_PATH = "/globex/remittance-advice";

  private static final Logger log = LoggerFactory.getLogger(GlobexSupplier.class);

  private final RestClient platform;
  private final Set<String> receivedAdvice = ConcurrentHashMap.newKeySet();
  private final Map<UUID, ReceivedCard> cardsByInvoice = new ConcurrentHashMap<>();

  GlobexSupplier(RestClient.Builder platform) {
    this.platform = platform.build();
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

  /** The card Globex received for the Invoice, if it has one: for demos, never with its PAN. */
  Optional<ReceivedCard> cardFor(UUID invoiceId) {
    return Optional.ofNullable(cardsByInvoice.get(invoiceId));
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
    var lastFour = card.getPan().substring(card.getPan().length() - 4);
    cardsByInvoice.put(
        advice.getInvoiceId(),
        new ReceivedCard(
            advice.getInvoiceNumber(), lastFour, card.getExpiresAt(), card.getAmount()));
    log.info(
        "Globex received the card ending {} for {}, {} {} minor units",
        lastFour,
        advice.getInvoiceNumber(),
        card.getAmount().getCurrency(),
        card.getAmount().getMinorUnits());
  }

  /** What Globex keeps of a card it received: enough to show, nothing to charge it with. */
  record ReceivedCard(
      String invoiceNumber, String lastFour, OffsetDateTime expiresAt, Money amount) {}
}
