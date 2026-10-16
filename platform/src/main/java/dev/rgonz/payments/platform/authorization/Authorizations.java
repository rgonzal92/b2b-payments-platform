package dev.rgonz.payments.platform.authorization;

import dev.rgonz.payments.platform.authorization.Authorization.ResponseCode;
import dev.rgonz.payments.platform.cards.Cards;
import dev.rgonz.payments.platform.cards.SingleUseCard;
import dev.rgonz.payments.platform.suppliers.Suppliers;
import dev.rgonz.payments.platform.vault.Vault;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers the Network's Authorization requests and records every answer. For a Single-Use Card the
 * Hold was placed at issuance, so an Authorization checks the card, never Available Credit
 * (ADR-0011).
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
   * Approves a Charge from the card's Supplier on an issued, {@code ACTIVE} card and moves the card
   * to {@code AUTHORIZED}; declines anything else, checking the rules in the design's order. The
   * PAN goes to the vault and nowhere else. The card's move and the recorded Authorization commit
   * together.
   */
  @Transactional
  Authorization authorize(Charge charge) {
    var cardToken = vault.cardFor(charge.pan()).orElse(null);
    var card = cardToken == null ? null : cards.find(cardToken).orElse(null);
    Authorization authorization;
    if (card == null) {
      authorization = Authorization.declined(ResponseCode.INVALID_CARD);
    } else if (card.status() != SingleUseCard.Status.ACTIVE) {
      authorization = Authorization.declined(ResponseCode.EXPIRED_CARD);
    } else if (!isFromTheCardsSupplier(charge, card)) {
      authorization = Authorization.declined(ResponseCode.NOT_PERMITTED);
    } else if (cards.authorize(cardToken)) {
      authorization = Authorization.approved(newAuthorizationCode());
    } else {
      // Another Charge moved the card first.
      authorization = Authorization.declined(ResponseCode.EXPIRED_CARD);
    }
    record(charge, cardToken, authorization);
    log.info(
        "Authorization {} answered {}", charge.requestId(), authorization.responseCode().code());
    return authorization;
  }

  // An exact match: the Network names the Supplier by the Acceptor ID its Acquirer gave it, and a
  // card whose Supplier has none can't be charged by anyone.
  private boolean isFromTheCardsSupplier(Charge charge, SingleUseCard card) {
    return suppliers.acceptorId(card.supplierId()).filter(charge.acceptorId()::equals).isPresent();
  }

  private void record(Charge charge, String cardToken, Authorization authorization) {
    jdbc.sql(
            """
            insert into "authorization".authorizations
                   (request_id, card_token, amount_minor, currency, acceptor_id, response_code,
                    authorization_code, status)
            values (:requestId, :cardToken, :amount, :currency, :acceptorId, :responseCode,
                    :authorizationCode, :status)
            """)
        .param("requestId", charge.requestId())
        .param("cardToken", cardToken)
        .param("amount", charge.amount().minorUnits())
        .param("currency", charge.amount().currency().getCurrencyCode())
        .param("acceptorId", charge.acceptorId())
        .param("responseCode", authorization.responseCode().code())
        .param("authorizationCode", authorization.authorizationCode())
        .param("status", authorization.status().name())
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
