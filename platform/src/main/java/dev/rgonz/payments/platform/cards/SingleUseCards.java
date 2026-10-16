package dev.rgonz.payments.platform.cards;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import dev.rgonz.payments.platform.vault.Vault;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class SingleUseCards implements Cards {

  private static final Duration VALIDITY = Duration.ofDays(30);
  private static final int CARD_TOKEN_BYTES = 16;

  private final JdbcClient jdbc;
  private final Vault vault;
  private final SecureRandom random = new SecureRandom();

  SingleUseCards(JdbcClient jdbc, Vault vault) {
    this.jdbc = jdbc;
    this.vault = vault;
  }

  // The saga runs one Payment's steps one at a time (its row lock), so nothing issues the same
  // Payment's card concurrently; the unique payment_id would refuse a second card anyway.
  @Override
  @Transactional
  public SingleUseCard issueSingleUse(
      UUID paymentId, BuyerId buyerId, UUID supplierId, Money amount, Instant now) {
    var issued = findForPayment(paymentId);
    if (issued.isPresent()) {
      return issued.get();
    }
    var cardToken = newCardToken();
    // PostgreSQL keeps microseconds, so the card returned now equals the card read back later.
    var issuedAt = now.truncatedTo(ChronoUnit.MICROS);
    var card =
        new SingleUseCard(
            cardToken,
            paymentId,
            buyerId,
            supplierId,
            amount,
            vault.generatePan(cardToken),
            issuedAt,
            issuedAt.plus(VALIDITY),
            SingleUseCard.Status.ACTIVE);
    jdbc.sql(
            """
            insert into cards.card
                   (card_token, payment_id, buyer_id, supplier_id, amount_minor, currency, last_four,
                    issued_at, expires_at, status)
            values (:cardToken, :paymentId, :buyerId, :supplierId, :amount, :currency, :lastFour,
                    :issuedAt, :expiresAt, :status)
            """)
        .param("cardToken", card.cardToken())
        .param("paymentId", card.paymentId())
        .param("buyerId", card.buyerId().value())
        .param("supplierId", card.supplierId())
        .param("amount", card.amount().minorUnits())
        .param("currency", card.amount().currency().getCurrencyCode())
        .param("lastFour", card.lastFour())
        .param("issuedAt", card.issuedAt().atOffset(ZoneOffset.UTC))
        .param("expiresAt", card.expiresAt().atOffset(ZoneOffset.UTC))
        .param("status", card.status().name())
        .update();
    return card;
  }

  @Override
  public Optional<SingleUseCard> findForPayment(UUID paymentId) {
    return jdbc.sql(
            """
            select card_token, payment_id, buyer_id, supplier_id, amount_minor, currency, last_four,
                   issued_at, expires_at, status
              from cards.card
             where payment_id = :paymentId
            """)
        .param("paymentId", paymentId)
        .query(SingleUseCards::toCard)
        .optional();
  }

  // Random and opaque: nothing about the card, its PAN included, can be worked out from it.
  private String newCardToken() {
    var bytes = new byte[CARD_TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static SingleUseCard toCard(ResultSet row, int rowNumber) throws SQLException {
    return new SingleUseCard(
        row.getString("card_token"),
        row.getObject("payment_id", UUID.class),
        new BuyerId(row.getObject("buyer_id", UUID.class)),
        row.getObject("supplier_id", UUID.class),
        new Money(row.getLong("amount_minor"), Currency.getInstance(row.getString("currency"))),
        row.getString("last_four"),
        row.getObject("issued_at", OffsetDateTime.class).toInstant(),
        row.getObject("expires_at", OffsetDateTime.class).toInstant(),
        SingleUseCard.Status.valueOf(row.getString("status")));
  }
}
