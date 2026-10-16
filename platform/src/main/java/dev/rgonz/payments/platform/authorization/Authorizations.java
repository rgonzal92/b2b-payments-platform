package dev.rgonz.payments.platform.authorization;

import dev.rgonz.payments.platform.authorization.Answer.ResponseCode;
import dev.rgonz.payments.platform.cards.Cards;
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
  private final SecureRandom random = new SecureRandom();

  Authorizations(JdbcClient jdbc, Vault vault, Cards cards) {
    this.jdbc = jdbc;
    this.vault = vault;
    this.cards = cards;
  }

  /**
   * Approves a Charge on an issued, {@code ACTIVE} card and moves the card to {@code AUTHORIZED};
   * declines anything else. The PAN goes to the vault and nowhere else. The card's move and the
   * recorded answer commit together.
   */
  @Transactional
  Answer authorize(Charge charge) {
    var cardToken = vault.cardFor(charge.pan()).orElse(null);
    Answer answer;
    if (cardToken == null) {
      answer = Answer.declined(ResponseCode.INVALID_CARD);
    } else if (cards.authorize(cardToken)) {
      answer = Answer.approved(newAuthorizationCode());
    } else {
      answer = Answer.declined(ResponseCode.EXPIRED_CARD);
    }
    record(charge, cardToken, answer);
    log.info("Authorization {} answered {}", charge.requestId(), answer.responseCode().code());
    return answer;
  }

  private void record(Charge charge, String cardToken, Answer answer) {
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
        .param("responseCode", answer.responseCode().code())
        .param("authorizationCode", answer.authorizationCode())
        .param("status", answer.status().name())
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
