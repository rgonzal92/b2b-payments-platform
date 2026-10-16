package dev.rgonz.payments.platform.suppliers;

import dev.rgonz.payments.platform.cards.Cards;
import dev.rgonz.payments.platform.cards.SingleUseCard;
import dev.rgonz.payments.platform.shared.Money;
import dev.rgonz.payments.platform.vault.CardDetails;
import dev.rgonz.payments.platform.vault.Vault;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Remittance Advice and their one-time links, stored by the hash of each link's token. */
@Repository
class RemittanceAdviceLinks {

  private final JdbcClient jdbc;
  private final LinkTokens tokens;
  private final Cards cards;
  private final Vault vault;

  RemittanceAdviceLinks(JdbcClient jdbc, LinkTokens tokens, Cards cards, Vault vault) {
    this.jdbc = jdbc;
    this.tokens = tokens;
    this.cards = cards;
    this.vault = vault;
  }

  /**
   * Records the advice unless its Payment already has one, and returns its link: the token, worked
   * out again each time, and the expiry first recorded. Commits on its own, ahead of the caller's
   * transaction, so the link works as soon as the Supplier receives it, even while the caller waits
   * for the Supplier's answer.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Link create(RemittanceAdvice advice, Instant expiresAt) {
    var token = tokens.tokenFor(advice.paymentId());
    jdbc.sql(
            """
            insert into suppliers.remittance_advice
                   (id, invoice_id, invoice_number, supplier_id, amount_minor, currency, card_token,
                    link_token_hash, link_expires_at)
            values (:id, :invoiceId, :invoiceNumber, :supplierId, :amount, :currency, :cardToken,
                    :linkTokenHash, :linkExpiresAt)
            on conflict (id) do nothing
            """)
        .param("id", advice.paymentId())
        .param("invoiceId", advice.invoiceId())
        .param("invoiceNumber", advice.invoiceNumber())
        .param("supplierId", advice.supplierId())
        .param("amount", advice.amount().minorUnits())
        .param("currency", advice.amount().currency().getCurrencyCode())
        .param("cardToken", advice.cardToken())
        .param("linkTokenHash", LinkTokens.hash(token))
        // PostgreSQL keeps microseconds, so every attempt sends the expiry exactly as stored.
        .param("linkExpiresAt", expiresAt.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
        .update();
    var recordedExpiry =
        jdbc.sql("select link_expires_at from suppliers.remittance_advice where id = :id")
            .param("id", advice.paymentId())
            .query(OffsetDateTime.class)
            .single();
    return new Link(token, recordedExpiry.toInstant());
  }

  /**
   * Uses up the link with this token and returns its card's details, fetched from the vault now and
   * never stored here. A link that shows nothing (unknown, used, expired, or its card cancelled)
   * isn't marked used.
   */
  @Transactional
  public Opening open(String token) {
    var tokenHash = LinkTokens.hash(token);
    var cardToken =
        jdbc.sql("select card_token from suppliers.remittance_advice where link_token_hash = :h")
            .param("h", tokenHash)
            .query(String.class)
            .optional();
    if (cardToken.isEmpty()) {
      return new Opening.Unknown();
    }
    var card = cards.find(cardToken.get()).orElseThrow();
    if (card.status() == SingleUseCard.Status.CANCELLED) {
      return new Opening.Gone();
    }
    // Reads the link and marks it used in one statement: of two opens at once, one updates the row
    // and the other waits for its lock, then finds it used.
    var used =
        jdbc.sql(
                """
                update suppliers.remittance_advice
                   set link_used_at = now()
                 where link_token_hash = :h
                   and link_used_at is null
                   and link_expires_at > now()
                returning invoice_number, amount_minor, currency
                """)
            .param("h", tokenHash)
            .query(
                (row, rowNumber) ->
                    new UsedLink(
                        row.getString("invoice_number"),
                        new Money(
                            row.getLong("amount_minor"),
                            Currency.getInstance(row.getString("currency")))))
            .optional();
    if (used.isEmpty()) {
      return new Opening.Gone();
    }
    return new Opening.Opened(
        used.get().invoiceNumber(), used.get().amount(), card, vault.cardDetails(card.cardToken()));
  }

  private record UsedLink(String invoiceNumber, Money amount) {}

  /** What opening a link found. */
  sealed interface Opening {

    /** An unused link within its 7 days: the card's details, from the vault, shown this once. */
    record Opened(String invoiceNumber, Money amount, SingleUseCard card, CardDetails details)
        implements Opening {}

    /** The link was opened before, has expired, or its card was cancelled. */
    record Gone() implements Opening {}

    /** No link has this token. */
    record Unknown() implements Opening {}
  }

  /** A Remittance Advice's one-time link. The token is a credential, so it isn't in toString. */
  record Link(String token, Instant expiresAt) {

    @Override
    public String toString() {
      return "Link[expiresAt=" + expiresAt + "]";
    }
  }
}
