package dev.rgonz.payments.platform.authorization;

import dev.rgonz.payments.platform.authorization.Authorization.ResponseCode;
import dev.rgonz.payments.platform.cards.Cards;
import dev.rgonz.payments.platform.suppliers.Suppliers;
import dev.rgonz.payments.platform.vault.Vault;
import java.security.SecureRandom;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers the Network's Authorization requests and records every answer, and cancels an
 * Authorization when the Network sends a Reversal for it. For a Single-Use Card the Hold was placed
 * at issuance, so an Authorization checks the card, never Available Credit (ADR-0011).
 */
@Repository
class Authorizations {

  private static final Logger log = LoggerFactory.getLogger(Authorizations.class);
  private static final String AUTHORIZATION_CODE_CHARACTERS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  private static final int AUTHORIZATION_CODE_LENGTH = 6;

  private final JdbcClient jdbc;
  private final Vault vault;
  private final Cards cards;
  private final Suppliers suppliers;
  private final SecureRandom random = new SecureRandom();

  Authorizations(JdbcClient jdbc, Vault vault, Cards cards, Suppliers suppliers) {
    this.jdbc = jdbc;
    this.vault = vault;
    this.cards = cards;
    this.suppliers = suppliers;
  }

  /**
   * Answers the Charge by the Authorization rules and records the answer; an approval also moves
   * the card to {@code AUTHORIZED}. A request ID that already has an answer gets that answer back,
   * whatever the rules would say now, and changes nothing; one a Reversal recorded first answers
   * {@code 05} (do not honour) the same way. The PAN and security code go to the vault and nowhere
   * else. The card's move and the recorded Authorization commit together.
   */
  @Transactional
  Authorization authorize(Charge charge, Instant now) {
    var cardToken = vault.cardFor(charge.pan()).orElse(null);
    var card = cardToken == null ? null : cards.find(cardToken).orElse(null);
    var suppliersAcceptorId =
        card == null ? null : suppliers.acceptorId(card.supplierId()).orElse(null);
    var securityCodeMatches =
        card != null && vault.securityCodeMatches(cardToken, charge.securityCode());
    var responseCode =
        AuthorizationRules.decide(charge, card, suppliersAcceptorId, securityCodeMatches, now);
    var authorization =
        responseCode == ResponseCode.APPROVED
            ? Authorization.approved(newAuthorizationCode())
            : Authorization.declined(responseCode);
    if (!insertUnlessAnswered(charge, cardToken, authorization)) {
      var stored = storedAnswer(charge.requestId());
      log.info(
          "Authorization {} already had an answer or a Reversal, and answered {}",
          charge.requestId(),
          stored.responseCode().code());
      return stored;
    }
    if (authorization.status() == Authorization.Status.APPROVED && !cards.authorize(cardToken)) {
      // A Charge with another request ID moved the card first, after this one read it ACTIVE.
      authorization = Authorization.declined(ResponseCode.NOT_PERMITTED);
      replaceAnswer(charge.requestId(), authorization);
    }
    log.info(
        "Authorization {} answered {}", charge.requestId(), authorization.responseCode().code());
    return authorization;
  }

  /**
   * Cancels the Authorization with this request ID: an approved one becomes {@code REVERSED} and
   * its card {@code ACTIVE} again, so the card can be charged again. A request ID with no
   * Authorization yet is recorded as reversed, so its Authorization, arriving later or still
   * running, answers {@code 05} and moves no card. The Hold stays: a Reversal never touches the
   * ledger.
   */
  @Transactional
  void reverse(String requestId) {
    if (claimAsReversed(requestId)) {
      log.info("Reversal {} came before its Authorization, which will answer 05", requestId);
      return;
    }
    // The request ID's row has committed, an Authorization's or an earlier Reversal's: the claim
    // waited for it. One conditional update locks the row and checks it is still APPROVED.
    var cardToken =
        jdbc.sql(
                """
                update "authorization".authorizations set status = :reversed
                 where request_id = :requestId and status = :approved
                returning card_token
                """)
            .param("reversed", Authorization.Status.REVERSED.name())
            .param("requestId", requestId)
            .param("approved", Authorization.Status.APPROVED.name())
            .query(String.class)
            .optional();
    cardToken.ifPresent(cards::reverse);
    log.info("Reversal {} acknowledged", requestId);
  }

  // Claims the request ID for a Reversal the way an Authorization claims it: whichever insert
  // commits first owns it. An Authorization still running when this commits then inserts nothing
  // and answers this row's 05; one that committed first makes this insert wait, then insert
  // nothing.
  private boolean claimAsReversed(String requestId) {
    return jdbc.sql(
                """
                insert into "authorization".authorizations (request_id, response_code, status)
                values (:requestId, :responseCode, :status)
                on conflict (request_id) do nothing
                """)
            .param("requestId", requestId)
            .param("responseCode", ResponseCode.DO_NOT_HONOUR.code())
            .param("status", Authorization.Status.REVERSED.name())
            .update()
        == 1;
  }

  // Records the answer unless the request ID already has one. The primary key decides, not a read
  // first: of two requests with one ID at once, the second's insert waits for the first to commit,
  // then inserts nothing, so only one of them ever moves the card.
  private boolean insertUnlessAnswered(
      Charge charge, String cardToken, Authorization authorization) {
    return jdbc.sql(
                """
                insert into "authorization".authorizations
                       (request_id, card_token, amount_minor, currency, acceptor_id, response_code,
                        authorization_code, status)
                values (:requestId, :cardToken, :amount, :currency, :acceptorId, :responseCode,
                        :authorizationCode, :status)
                on conflict (request_id) do nothing
                """)
            .param("requestId", charge.requestId())
            .param("cardToken", cardToken)
            .param("amount", charge.amountMinor())
            .param("currency", charge.currencyCode())
            .param("acceptorId", charge.acceptorId())
            .param("responseCode", authorization.responseCode().code())
            .param("authorizationCode", authorization.authorizationCode())
            .param("status", authorization.status().name())
            .update()
        == 1;
  }

  private Authorization storedAnswer(String requestId) {
    return jdbc.sql(
            """
            select response_code, authorization_code
              from "authorization".authorizations
             where request_id = :requestId
            """)
        .param("requestId", requestId)
        .query(
            (row, rowNumber) ->
                new Authorization(
                    ResponseCode.of(row.getString("response_code")),
                    row.getString("authorization_code")))
        .single();
  }

  private void replaceAnswer(String requestId, Authorization authorization) {
    jdbc.sql(
            """
            update "authorization".authorizations
               set response_code = :responseCode, authorization_code = :authorizationCode,
                   status = :status
             where request_id = :requestId
            """)
        .param("responseCode", authorization.responseCode().code())
        .param("authorizationCode", authorization.authorizationCode())
        .param("status", authorization.status().name())
        .param("requestId", requestId)
        .update();
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
}
