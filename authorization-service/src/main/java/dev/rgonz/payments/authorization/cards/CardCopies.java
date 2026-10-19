package dev.rgonz.payments.authorization.cards;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * The service's copy of the cards, in its own PostgreSQL through R2DBC. Kafka delivers each event
 * at least once, so every write is keyed on the event's natural ID, the Card Token and the event
 * type, and does nothing the second time: a repeated {@code CardIssued} finds the card already
 * there, and a repeated {@code CardCancelled} or {@code CardExpired} finds it already final.
 */
@Repository
public class CardCopies {

  private final DatabaseClient db;

  CardCopies(DatabaseClient db) {
    this.db = db;
  }

  /** The copy of the card, or empty if no {@code CardIssued} for it has arrived. */
  public Mono<CardCopy> find(String cardToken) {
    return db.sql(
            """
            select card_token, pan_hash, security_code_hash, expires_at, amount_minor, currency,
                   acceptor_id, status
              from card_copy
             where card_token = :cardToken
            """)
        .bind("cardToken", cardToken)
        .map(
            row ->
                new CardCopy(
                    row.get("card_token", String.class),
                    row.get("pan_hash", String.class),
                    row.get("security_code_hash", String.class),
                    row.get("expires_at", OffsetDateTime.class).toInstant(),
                    row.get("amount_minor", Long.class),
                    Currency.getInstance(row.get("currency", String.class)),
                    row.get("acceptor_id", String.class),
                    CardStatus.valueOf(row.get("status", String.class))))
        .one();
  }

  /** Applies a card event to the copy; an event already applied changes nothing. */
  Mono<Void> apply(CardEvent event) {
    return switch (event) {
      case CardEvent.CardIssued issued -> insertUnlessPresent(issued);
      case CardEvent.CardCancelled cancelled ->
          endUnlessFinal(cancelled.cardToken(), CardStatus.CANCELLED);
      case CardEvent.CardExpired expired -> endUnlessFinal(expired.cardToken(), CardStatus.EXPIRED);
    };
  }

  private Mono<Void> insertUnlessPresent(CardEvent.CardIssued issued) {
    var spec =
        db.sql(
                """
                insert into card_copy
                       (card_token, pan_hash, security_code_hash, expires_at, amount_minor,
                        currency, acceptor_id, status)
                values (:cardToken, :panHash, :securityCodeHash, :expiresAt, :amountMinor,
                        :currency, :acceptorId, 'ACTIVE')
                on conflict (card_token) do nothing
                """)
            .bind("cardToken", issued.cardToken())
            .bind("panHash", issued.panHash())
            .bind("securityCodeHash", issued.securityCodeHash())
            .bind("expiresAt", utc(issued.expiresAt()))
            .bind("amountMinor", issued.amountMinorUnits())
            .bind("currency", issued.currency().getCurrencyCode());
    spec =
        issued.acceptorId() == null
            ? spec.bindNull("acceptorId", String.class)
            : spec.bind("acceptorId", issued.acceptorId());
    return spec.then();
  }

  // CANCELLED and EXPIRED are final, so the first to arrive stays.
  private Mono<Void> endUnlessFinal(String cardToken, CardStatus status) {
    return db.sql(
            """
            update card_copy
               set status = :status
             where card_token = :cardToken and status not in ('CANCELLED', 'EXPIRED')
            """)
        .bind("status", status.name())
        .bind("cardToken", cardToken)
        .then();
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
