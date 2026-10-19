package dev.rgonz.payments.authorization.cards;

import dev.rgonz.payments.authorization.outbox.Outbox;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

/**
 * The service's copy of the cards, in its own PostgreSQL through R2DBC. Kafka delivers each event
 * at least once, so every write is keyed on the event's natural ID, the Card Token and the event
 * type, and does nothing the second time. The platform may also send one card's events out of
 * order, so they apply in any order to the same result: a status only moves forward, from {@code
 * ACTIVE} or {@code AUTHORIZED} to {@code CANCELLED} or {@code EXPIRED}, then to {@code CLEARED},
 * all final: a Clearing clears a card whatever its status, and nothing moves it after. The
 * service's own answers move a card between {@code ACTIVE} and {@code AUTHORIZED}. A card that ends
 * before its {@code CardIssued} arrives is kept with its final status, and the {@code CardIssued}
 * then fills in its details without making it {@code ACTIVE}. A card first stored with its details
 * is on file: a {@link CardOnFile} goes to the {@link Outbox} in the same transaction.
 */
@Repository
public class CardCopies {

  private static final String SELECT =
      """
      select card_token, pan_hash, security_code_hash, expires_at, amount_minor, currency,
             acceptor_id, status
        from card_copy
      """;

  private final DatabaseClient db;
  private final Outbox outbox;

  CardCopies(DatabaseClient db, Outbox outbox) {
    this.db = db;
    this.outbox = outbox;
  }

  /** The copy of the card, or empty if no event for it has arrived. */
  public Mono<CardCopy> find(String cardToken) {
    return db.sql(SELECT + " where card_token = :cardToken")
        .bind("cardToken", cardToken)
        .map(CardCopies::copy)
        .one();
  }

  /**
   * The copy of the card whose PAN has this keyed hash ({@link CardHashing}), or empty if none has.
   * A card that ended before its {@code CardIssued} arrived has no hash yet, so it's never found.
   */
  public Mono<CardCopy> findByPanHash(String panHash) {
    return db.sql(SELECT + " where pan_hash = :panHash")
        .bind("panHash", panHash)
        .map(CardCopies::copy)
        .one();
  }

  /**
   * Moves the card from {@code ACTIVE} to {@code AUTHORIZED} for an approved Charge, in the
   * caller's transaction. False when it isn't {@code ACTIVE} any more: another Charge moved it
   * first, or the platform ended it, since the caller read it. The update takes the row's lock, so
   * of two approvals at once the second waits, then finds it {@code AUTHORIZED}.
   */
  public Mono<Boolean> moveToAuthorized(String cardToken) {
    return move(cardToken, CardStatus.ACTIVE, CardStatus.AUTHORIZED);
  }

  /**
   * Moves the card back from {@code AUTHORIZED} to {@code ACTIVE} when its Authorization is
   * reversed, in the caller's transaction. Only an {@code AUTHORIZED} card moves, so a card the
   * platform ended since stays ended.
   */
  public Mono<Void> moveToActive(String cardToken) {
    return move(cardToken, CardStatus.AUTHORIZED, CardStatus.ACTIVE).then();
  }

  /** Applies a card event to the copy; an event already applied changes nothing. */
  @Transactional
  Mono<Void> apply(CardEvent event) {
    return switch (event) {
      case CardEvent.CardIssued issued -> insertUnlessPresent(issued);
      case CardEvent.CardCancelled cancelled ->
          endUnlessFinal(cancelled.cardToken(), CardStatus.CANCELLED);
      case CardEvent.CardExpired expired -> endUnlessFinal(expired.cardToken(), CardStatus.EXPIRED);
      case CardEvent.CardCleared cleared -> endUnlessFinal(cleared.cardToken(), CardStatus.CLEARED);
    };
  }

  // The insert returns the row it wrote: ACTIVE for a card stored for the first time, which is now
  // on file; the final status for one that ended first, which never is; nothing for a repeat.
  private Mono<Void> insertUnlessPresent(CardEvent.CardIssued issued) {
    var spec =
        db.sql(
                """
                insert into card_copy
                       (card_token, pan_hash, security_code_hash, expires_at, amount_minor,
                        currency, acceptor_id, status)
                values (:cardToken, :panHash, :securityCodeHash, :expiresAt, :amountMinor,
                        :currency, :acceptorId, 'ACTIVE')
                on conflict (card_token) do update
                   set pan_hash = excluded.pan_hash,
                       security_code_hash = excluded.security_code_hash,
                       expires_at = excluded.expires_at,
                       amount_minor = excluded.amount_minor,
                       currency = excluded.currency,
                       acceptor_id = excluded.acceptor_id
                 where card_copy.pan_hash is null
                returning status
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
    return spec.map(row -> CardStatus.valueOf(row.get("status", String.class)))
        .one()
        .filter(status -> status == CardStatus.ACTIVE)
        .flatMap(active -> outbox.append(issued.cardToken(), new CardOnFile(issued.cardToken())));
  }

  // CANCELLED, EXPIRED and CLEARED are final. CLEARED replaces the other two, as the platform
  // clears a card whatever its status; otherwise the first to arrive stays. A card not seen yet is
  // kept with only its token and final status, for its CardIssued to fill in.
  private Mono<Void> endUnlessFinal(String cardToken, CardStatus status) {
    return db.sql(
            """
            insert into card_copy (card_token, status)
            values (:cardToken, :status)
            on conflict (card_token) do update
               set status = excluded.status
             where card_copy.status not in (:finals)
            """)
        .bind("status", status.name())
        .bind(
            "finals",
            status == CardStatus.CLEARED
                ? List.of(CardStatus.CLEARED.name())
                : List.of(
                    CardStatus.CANCELLED.name(),
                    CardStatus.EXPIRED.name(),
                    CardStatus.CLEARED.name()))
        .bind("cardToken", cardToken)
        .then();
  }

  private Mono<Boolean> move(String cardToken, CardStatus from, CardStatus to) {
    return db.sql(
            "update card_copy set status = :to where card_token = :cardToken and status = :from")
        .bind("to", to.name())
        .bind("cardToken", cardToken)
        .bind("from", from.name())
        .fetch()
        .rowsUpdated()
        .map(rows -> rows == 1);
  }

  private static CardCopy copy(Readable row) {
    return new CardCopy(
        row.get("card_token", String.class),
        row.get("pan_hash", String.class),
        row.get("security_code_hash", String.class),
        instant(row.get("expires_at", OffsetDateTime.class)),
        row.get("amount_minor", Long.class),
        currency(row.get("currency", String.class)),
        row.get("acceptor_id", String.class),
        CardStatus.valueOf(row.get("status", String.class)));
  }

  private static Instant instant(OffsetDateTime timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static Currency currency(String code) {
    return code == null ? null : Currency.getInstance(code);
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
