package dev.rgonz.payments.platform.authorization;

import dev.rgonz.payments.platform.cards.Cards;
import dev.rgonz.payments.platform.cards.SingleUseCard;
import dev.rgonz.payments.platform.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The platform's copy of the Authorizations authorization-service approved, and of the ones the
 * Network reversed since, fed by the service's events (M5 design, "The cards the service holds").
 * Each event moves the platform's card in the same transaction as the copy, so the 7-day expiry
 * finds AUTHORIZED cards and Clearing matches as before. The service answers the Network; the
 * platform only follows.
 *
 * <p>Kafka delivers at least once, so each write is keyed on the request ID and the status it moves
 * from: an event applied once changes nothing the second time.
 */
@Repository
class Authorizations implements ApprovedAuthorizations {

  private static final Logger log = LoggerFactory.getLogger(Authorizations.class);
  private static final String APPROVED = "APPROVED";
  private static final String REVERSED = "REVERSED";
  // The copy keeps the table's DE 39 column: every Authorization in it was approved.
  private static final String APPROVED_RESPONSE_CODE = "00";

  private final JdbcClient jdbc;
  private final Cards cards;

  Authorizations(JdbcClient jdbc, Cards cards) {
    this.jdbc = jdbc;
    this.cards = cards;
  }

  /**
   * Stores the approved Authorization and moves its card to {@code AUTHORIZED} from {@code ACTIVE}.
   * A card the platform ended meanwhile, {@code EXPIRED} for one, stays as it is, and its Clearing
   * finds the Authorization anyway. A request ID already stored, approved or reversed, changes
   * nothing: a repeat, or an approved Authorization arriving after its Reversal.
   */
  @Transactional
  void approved(AuthorizationEvent event, Instant authorizedAt) {
    if (!insert(event, APPROVED)) {
      log.info("Authorization {} already stored, nothing changed", event.requestId());
      return;
    }
    if (!cards.authorize(event.cardToken(), authorizedAt)) {
      log.info(
          "Authorization {} stored; its card {} wasn't ACTIVE and stays as it is",
          event.requestId(),
          event.cardToken());
    }
  }

  /**
   * Marks the approved Authorization reversed and moves its card back to {@code ACTIVE} from {@code
   * AUTHORIZED}: a card {@code CLEARED} or {@code EXPIRED} meanwhile stays as it is. One already
   * reversed changes nothing. One not stored yet is stored reversed, so its approved Authorization,
   * if it came later, would change nothing; the service sends both on the Card Token's partition in
   * order, so this is a guard, not a path.
   */
  @Transactional
  void reversed(AuthorizationEvent event) {
    var cardToken =
        jdbc.sql(
                """
                update "authorization".authorizations set status = :reversed
                 where request_id = :requestId and status = :approved
                returning card_token
                """)
            .param("reversed", REVERSED)
            .param("requestId", event.requestId())
            .param("approved", APPROVED)
            .query(String.class)
            .optional();
    if (cardToken.isPresent()) {
      cards.reverse(cardToken.get());
      log.info("Authorization {} reversed", event.requestId());
    } else if (insert(event, REVERSED)) {
      log.warn(
          "Authorization {} reversed before its approved Authorization arrived", event.requestId());
    } else {
      log.info("Authorization {} already reversed, nothing changed", event.requestId());
    }
  }

  // Only an approved Authorization has an authorization code, and keeps it once reversed.
  @Override
  public Optional<ApprovedAuthorization> find(String cardToken, String authorizationCode) {
    return jdbc.sql(
            """
            select request_id, amount_minor, currency, status
              from "authorization".authorizations
             where card_token = :cardToken
               and authorization_code = :authorizationCode
            """)
        .param("cardToken", cardToken)
        .param("authorizationCode", authorizationCode)
        .query(
            (row, rowNumber) ->
                new ApprovedAuthorization(
                    row.getString("request_id"),
                    new Money(
                        row.getLong("amount_minor"),
                        Currency.getInstance(row.getString("currency"))),
                    REVERSED.equals(row.getString("status"))))
        .optional();
  }

  // A card is AUTHORIZED by one approved Authorization at a time, and a Reversal makes that one
  // REVERSED before another can be approved, so an expired card has at most one APPROVED
  // Authorization. Every approved Authorization has an authorization code, which lets the lookup
  // use the (card, code) index.
  @Override
  public List<NeverClearedAuthorization> neverClearedOn(LocalDate day) {
    return cards.expiredUnclearedOn(day).stream()
        .flatMap(card -> approvedFor(card).stream())
        .toList();
  }

  private Optional<NeverClearedAuthorization> approvedFor(SingleUseCard card) {
    return jdbc.sql(
            """
            select request_id, amount_minor, currency
              from "authorization".authorizations
             where card_token = :cardToken and status = :approved
               and authorization_code is not null
            """)
        .param("cardToken", card.cardToken())
        .param("approved", APPROVED)
        .query(
            (row, rowNumber) ->
                new NeverClearedAuthorization(
                    row.getString("request_id"),
                    card.cardToken(),
                    card.buyerId(),
                    new Money(
                        row.getLong("amount_minor"),
                        Currency.getInstance(row.getString("currency")))))
        .optional();
  }

  // The primary key decides, not a read first: a repeat inserts nothing.
  private boolean insert(AuthorizationEvent event, String status) {
    return jdbc.sql(
                """
                insert into "authorization".authorizations
                       (request_id, card_token, amount_minor, currency, acceptor_id, response_code,
                        authorization_code, status)
                values (:requestId, :cardToken, :amount, :currency, :acceptorId, :responseCode,
                        :authorizationCode, :status)
                on conflict (request_id) do nothing
                """)
            .param("requestId", event.requestId())
            .param("cardToken", event.cardToken())
            .param("amount", event.amountMinorUnits())
            .param("currency", event.currency())
            .param("acceptorId", event.acceptorId())
            .param("responseCode", APPROVED_RESPONSE_CODE)
            .param("authorizationCode", event.authorizationCode())
            .param("status", status)
            .update()
        == 1;
  }
}
