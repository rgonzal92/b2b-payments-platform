package dev.rgonz.payments.authorization.network;

import dev.rgonz.payments.authorization.cards.CardCopies;
import dev.rgonz.payments.authorization.cards.CardCopy;
import dev.rgonz.payments.authorization.cards.CardHashing;
import dev.rgonz.payments.authorization.network.Authorization.ResponseCode;
import java.security.SecureRandom;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

/**
 * Answers the Network's Authorization requests from the service's copy of the cards and records
 * every answer, and cancels an Authorization when the Network sends a Reversal for it: the
 * platform's rules and race safety (M2 design), through R2DBC, so no call waits on a thread for the
 * database. Each method returns a {@link Mono} that does nothing until subscribed; its statements
 * run in one transaction, which commits when it completes.
 */
@Repository
class Authorizations {

  private static final Logger log = LoggerFactory.getLogger(Authorizations.class);
  private static final String AUTHORIZATION_CODE_CHARACTERS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  private static final int AUTHORIZATION_CODE_LENGTH = 6;

  private final DatabaseClient db;
  private final CardCopies cards;
  private final CardHashing hashing;
  private final SecureRandom random = new SecureRandom();

  Authorizations(DatabaseClient db, CardCopies cards, CardHashing hashing) {
    this.db = db;
    this.cards = cards;
    this.hashing = hashing;
  }

  /**
   * Answers the Charge by the Authorization rules and records the answer; an approval also moves
   * the card to {@code AUTHORIZED}. A request ID that already has an answer gets that answer back,
   * whatever the rules would say now, and changes nothing; one a Reversal recorded first answers
   * {@code 05} (do not honour) the same way. The PAN and security code are hashed in memory and
   * compared with the card's hashes, never stored or logged. The card's move and the recorded
   * Authorization commit together.
   */
  @Transactional
  Mono<Authorization> authorize(Charge charge, Instant now) {
    return Mono.defer(() -> cards.findByPanHash(hashing.hash(charge.pan())))
        .map(card -> new Decision(card.cardToken(), decide(charge, card, now)))
        .switchIfEmpty(Mono.fromSupplier(() -> new Decision(null, decide(charge, null, now))))
        .flatMap(
            decision ->
                insertUnlessAnswered(charge, decision.cardToken(), decision.authorization())
                    .flatMap(
                        inserted ->
                            inserted
                                ? moveCard(charge.requestId(), decision)
                                : storedAnswer(charge.requestId())));
  }

  /**
   * Cancels the Authorization with this request ID: an approved one becomes {@code REVERSED} and
   * its card {@code ACTIVE} again, so the card can be charged again. A request ID with no
   * Authorization yet is recorded as reversed, so its Authorization, arriving later or still
   * running, answers {@code 05} and moves no card.
   */
  @Transactional
  Mono<Void> reverse(String requestId) {
    return claimAsReversed(requestId)
        .flatMap(
            claimed ->
                claimed
                    ? Mono.<Void>fromRunnable(
                        () ->
                            log.info(
                                "Reversal {} came before its Authorization, which will answer 05",
                                requestId))
                    : reverseIfApproved(requestId)
                        .then(
                            Mono.<Void>fromRunnable(
                                () -> log.info("Reversal {} acknowledged", requestId))));
  }

  private Authorization decide(Charge charge, CardCopy card, Instant now) {
    var securityCodeMatches =
        card != null && hashing.matches(charge.securityCode(), card.securityCodeHash());
    var responseCode = AuthorizationRules.decide(charge, card, securityCodeMatches, now);
    return responseCode == ResponseCode.APPROVED
        ? Authorization.approved(newAuthorizationCode())
        : Authorization.declined(responseCode);
  }

  // An approval moves the card only if it's still ACTIVE: a Charge with another request ID may have
  // moved it first, after this one read it ACTIVE, and this one is then declined with 57.
  private Mono<Authorization> moveCard(String requestId, Decision decision) {
    var authorization = decision.authorization();
    var answer =
        authorization.status() != Authorization.Status.APPROVED
            ? Mono.just(authorization)
            : cards
                .moveToAuthorized(decision.cardToken())
                .flatMap(
                    moved -> {
                      if (moved) {
                        return Mono.just(authorization);
                      }
                      var declined = Authorization.declined(ResponseCode.NOT_PERMITTED);
                      return replaceAnswer(requestId, declined).thenReturn(declined);
                    });
    return answer.doOnNext(
        answered ->
            log.info("Authorization {} answered {}", requestId, answered.responseCode().code()));
  }

  // The request ID's row has committed, an Authorization's or an earlier Reversal's: the claim
  // waited for it. One conditional update locks the row and checks it is still APPROVED.
  private Mono<Void> reverseIfApproved(String requestId) {
    return db.sql(
            """
            update authorizations set status = :reversed
             where request_id = :requestId and status = :approved
            returning card_token
            """)
        .bind("reversed", Authorization.Status.REVERSED.name())
        .bind("requestId", requestId)
        .bind("approved", Authorization.Status.APPROVED.name())
        .map(row -> row.get("card_token", String.class))
        .one()
        .flatMap(cards::moveToActive);
  }

  // Claims the request ID for a Reversal the way an Authorization claims it: whichever insert
  // commits first owns it. An Authorization still running when this commits then inserts nothing
  // and answers this row's 05; one that committed first makes this insert wait, then insert
  // nothing.
  private Mono<Boolean> claimAsReversed(String requestId) {
    return db.sql(
            """
            insert into authorizations (request_id, response_code, status)
            values (:requestId, :responseCode, :reversed)
            on conflict (request_id) do nothing
            """)
        .bind("requestId", requestId)
        .bind("responseCode", ResponseCode.DO_NOT_HONOUR.code())
        .bind("reversed", Authorization.Status.REVERSED.name())
        .fetch()
        .rowsUpdated()
        .map(rows -> rows == 1);
  }

  // Records the answer unless the request ID already has one. The primary key decides, not a read
  // first: of two requests with one ID at once, the second's insert waits for the first to commit,
  // then inserts nothing, so only one of them ever moves the card.
  private Mono<Boolean> insertUnlessAnswered(
      Charge charge, String cardToken, Authorization authorization) {
    var spec =
        db.sql(
                """
                insert into authorizations
                       (request_id, card_token, amount_minor, currency, acceptor_id, response_code,
                        authorization_code, status)
                values (:requestId, :cardToken, :amount, :currency, :acceptorId, :responseCode,
                        :authorizationCode, :status)
                on conflict (request_id) do nothing
                """)
            .bind("requestId", charge.requestId())
            .bind("amount", charge.amountMinor())
            .bind("currency", charge.currencyCode())
            .bind("acceptorId", charge.acceptorId())
            .bind("responseCode", authorization.responseCode().code())
            .bind("status", authorization.status().name());
    spec = bindNullable(spec, "cardToken", cardToken);
    spec = bindNullable(spec, "authorizationCode", authorization.authorizationCode());
    return spec.fetch().rowsUpdated().map(rows -> rows == 1);
  }

  private Mono<Authorization> storedAnswer(String requestId) {
    return db.sql(
            """
            select response_code, authorization_code
              from authorizations
             where request_id = :requestId
            """)
        .bind("requestId", requestId)
        .map(
            row ->
                new Authorization(
                    ResponseCode.of(row.get("response_code", String.class)),
                    row.get("authorization_code", String.class)))
        .one()
        .doOnNext(
            stored ->
                log.info(
                    "Authorization {} already had an answer or a Reversal, and answered {}",
                    requestId,
                    stored.responseCode().code()));
  }

  private Mono<Void> replaceAnswer(String requestId, Authorization authorization) {
    return bindNullable(
            db.sql(
                    """
                    update authorizations
                       set response_code = :responseCode, authorization_code = :authorizationCode,
                           status = :status
                     where request_id = :requestId
                    """)
                .bind("responseCode", authorization.responseCode().code())
                .bind("status", authorization.status().name())
                .bind("requestId", requestId),
            "authorizationCode",
            authorization.authorizationCode())
        .then();
  }

  private static GenericExecuteSpec bindNullable(
      GenericExecuteSpec spec, String name, String value) {
    return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
  }

  // DE 38 is six alphanumeric characters; random, so one approval's code says nothing of another's.
  private String newAuthorizationCode() {
    var code = new StringBuilder(AUTHORIZATION_CODE_LENGTH);
    for (var i = 0; i < AUTHORIZATION_CODE_LENGTH; i++) {
      code.append(
          AUTHORIZATION_CODE_CHARACTERS.charAt(
              random.nextInt(AUTHORIZATION_CODE_CHARACTERS.length())));
    }
    return code.toString();
  }

  /** What the rules decided, and the card it's for: null when no card has the PAN. */
  private record Decision(String cardToken, Authorization authorization) {}
}
