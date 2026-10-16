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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class SingleUseCards implements Cards {

  private static final Duration VALIDITY = Duration.ofDays(30);
  // No Clearing within 7 days of an Authorization means the Charge isn't coming (M2 design, Q40).
  private static final Duration CLEARING_WINDOW = Duration.ofDays(7);
  private static final int CARD_TOKEN_BYTES = 16;
  private static final String COLUMNS =
      """
      card_token, payment_id, buyer_id, supplier_id, amount_minor, currency, last_four, issued_at,
      expires_at, status
      """;

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
    return jdbc.sql("select " + COLUMNS + " from cards.card where payment_id = :paymentId")
        .param("paymentId", paymentId)
        .query(SingleUseCards::toCard)
        .optional();
  }

  @Override
  public Optional<SingleUseCard> find(String cardToken) {
    return jdbc.sql("select " + COLUMNS + " from cards.card where card_token = :cardToken")
        .param("cardToken", cardToken)
        .query(SingleUseCards::toCard)
        .optional();
  }

  // Only an ACTIVE card moves, so a card charged, expired or cleared meanwhile keeps its status.
  @Override
  public void cancel(String cardToken) {
    jdbc.sql(
            """
            update cards.card set status = :cancelled
             where card_token = :cardToken and status = :active
            """)
        .param("cancelled", SingleUseCard.Status.CANCELLED.name())
        .param("cardToken", cardToken)
        .param("active", SingleUseCard.Status.ACTIVE.name())
        .update();
  }

  // Only an ACTIVE card moves, so of two Charges at once exactly one finds it ACTIVE.
  @Override
  public boolean authorize(String cardToken, Instant now) {
    return jdbc.sql(
                """
                update cards.card set status = :authorized, authorized_at = :now
                 where card_token = :cardToken and status = :active
                """)
            .param("authorized", SingleUseCard.Status.AUTHORIZED.name())
            .param("now", now.atOffset(ZoneOffset.UTC))
            .param("cardToken", cardToken)
            .param("active", SingleUseCard.Status.ACTIVE.name())
            .update()
        == 1;
  }

  // Only an AUTHORIZED card moves, so a card cancelled since its Charge stays cancelled.
  @Override
  public void reverse(String cardToken) {
    jdbc.sql(
            """
            update cards.card set status = :active, authorized_at = null
             where card_token = :cardToken and status = :authorized
            """)
        .param("active", SingleUseCard.Status.ACTIVE.name())
        .param("cardToken", cardToken)
        .param("authorized", SingleUseCard.Status.AUTHORIZED.name())
        .update();
  }

  // Any status moves: a card the expiry job holds locked waits for it to commit, then clears.
  @Override
  public void clear(String cardToken) {
    jdbc.sql(
            """
            update cards.card set status = :cleared
             where card_token = :cardToken and status <> :cleared
            """)
        .param("cleared", SingleUseCard.Status.CLEARED.name())
        .param("cardToken", cardToken)
        .update();
  }

  /**
   * Marks up to {@code batchSize} {@code ACTIVE} cards whose expiry is at or before {@code now}
   * {@code EXPIRED} and returns them, skipping cards other transactions have locked. Joins the
   * caller's transaction, which keeps them locked until it ends.
   */
  List<SingleUseCard> expireUncharged(Instant now, int batchSize) {
    // The literal 'ACTIVE' matches card_expires_at_idx's predicate; a bound parameter wouldn't,
    // once PostgreSQL switches the prepared statement to a generic plan.
    return jdbc.sql(
            """
            update cards.card
               set status = 'EXPIRED'
             where card_token in (select card_token
                                    from cards.card
                                   where status = 'ACTIVE'
                                     and expires_at <= :now
                                   order by expires_at
                                   limit :batchSize
                                     for update skip locked)
            returning
            """
                + COLUMNS)
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("batchSize", batchSize)
        .query(SingleUseCards::toCard)
        .list();
  }

  /**
   * Marks up to {@code batchSize} {@code AUTHORIZED} cards authorized 7 days or more before {@code
   * now} {@code EXPIRED} and returns them, skipping cards other transactions have locked. Joins the
   * caller's transaction, which keeps them locked until it ends.
   */
  List<SingleUseCard> expireUncleared(Instant now, int batchSize) {
    // The literal 'AUTHORIZED' matches card_authorized_at_idx's predicate, as in expireUncharged.
    return jdbc.sql(
            """
            update cards.card
               set status = 'EXPIRED'
             where card_token in (select card_token
                                    from cards.card
                                   where status = 'AUTHORIZED'
                                     and authorized_at <= :authorizedBy
                                   order by authorized_at
                                   limit :batchSize
                                     for update skip locked)
            returning
            """
                + COLUMNS)
        .param("authorizedBy", now.minus(CLEARING_WINDOW).atOffset(ZoneOffset.UTC))
        .param("batchSize", batchSize)
        .query(SingleUseCards::toCard)
        .list();
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
