package dev.rgonz.payments.platform.suppliers;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Remittance Advice and their one-time links, stored by the hash of each link's token. */
@Repository
class RemittanceLinks {

  private final JdbcClient jdbc;
  private final LinkTokens tokens;

  RemittanceLinks(JdbcClient jdbc, LinkTokens tokens) {
    this.jdbc = jdbc;
    this.tokens = tokens;
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

  /** A Remittance Advice's one-time link. The token is a credential, so it isn't in toString. */
  record Link(String token, Instant expiresAt) {

    @Override
    public String toString() {
      return "Link[expiresAt=" + expiresAt + "]";
    }
  }
}
