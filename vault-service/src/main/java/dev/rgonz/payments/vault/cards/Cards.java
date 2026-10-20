package dev.rgonz.payments.vault.cards;

import dev.rgonz.payments.vault.openbao.Transit;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The Card Vault's cards: each one's PAN and security code, encrypted under a data key of its own
 * that only OpenBao Transit can unwrap (envelope encryption), with the PAN's keyed hash and the
 * card's expiry beside them.
 */
@Repository
class Cards {

  /** What the Issuer may know of a card: never its PAN or security code. */
  record IssuedCard(String lastFour, String panHash, String securityCodeHash) {}

  // A collision is one in a billion per card already issued; ten in a row means a bug, not chance.
  private static final int MAX_PAN_DRAWS = 10;

  private final JdbcClient jdbc;
  private final Transit transit;
  private final CardHashing hashing;
  private final RandomGenerator random;

  @Autowired
  Cards(JdbcClient jdbc, Transit transit, CardHashing hashing) {
    this(jdbc, transit, hashing, new SecureRandom());
  }

  // Tests pass their own draws, to force a PAN another card already has.
  Cards(JdbcClient jdbc, Transit transit, CardHashing hashing, RandomGenerator random) {
    this.jdbc = jdbc;
    this.transit = transit;
    this.hashing = hashing;
    this.random = random;
  }

  /**
   * Issues the card with this Card Token, or returns it as first issued if it already exists: a
   * caller retrying after a lost answer gets the same card, whatever expiry it sends. One Transit
   * call either way, {@code datakey} for a new card and {@code decrypt} for a repeat.
   */
  IssuedCard issue(String cardToken, Instant expiresAt) {
    var existing = find(cardToken);
    if (existing.isPresent()) {
      return existing.get();
    }
    var dataKey = transit.newDataKey();
    try {
      var securityCode = Pans.newSecurityCode(random);
      for (var draw = 1; draw <= MAX_PAN_DRAWS; draw++) {
        var pan = Pans.newPan(random);
        var card = lastFourAndHashes(pan, securityCode);
        if (insert(cardToken, pan, card.panHash(), securityCode, dataKey, expiresAt)) {
          return card;
        }
        // Either another card has this PAN, and a new one is drawn, or a concurrent call with the
        // same Card Token got in first, and its card is the one.
        var concurrent = find(cardToken);
        if (concurrent.isPresent()) {
          return concurrent.get();
        }
      }
    } finally {
      Arrays.fill(dataKey.plaintext(), (byte) 0);
    }
    throw new IllegalStateException(
        "Drew " + MAX_PAN_DRAWS + " PANs that were all taken for card " + cardToken + ".");
  }

  // A class, not a record: a record's equals, hashCode and toString would use its arrays' identity
  // (Sonar's S6218).
  private static final class StoredCard {
    private final byte[] panCiphertext;
    private final byte[] securityCodeCiphertext;
    private final String wrappedDataKey;

    StoredCard(byte[] panCiphertext, byte[] securityCodeCiphertext, String wrappedDataKey) {
      this.panCiphertext = panCiphertext;
      this.securityCodeCiphertext = securityCodeCiphertext;
      this.wrappedDataKey = wrappedDataKey;
    }
  }

  // Read first, unwrapped after: the Transit call holds no database connection.
  private Optional<IssuedCard> find(String cardToken) {
    return jdbc.sql(
            """
            select pan_ciphertext, security_code_ciphertext, wrapped_data_key
              from card
             where card_token = :cardToken
            """)
        .param("cardToken", cardToken)
        .query(
            (row, rowNumber) ->
                new StoredCard(
                    row.getBytes("pan_ciphertext"),
                    row.getBytes("security_code_ciphertext"),
                    row.getString("wrapped_data_key")))
        .optional()
        .map(stored -> lastFourAndHashes(stored, cardToken));
  }

  private IssuedCard lastFourAndHashes(StoredCard stored, String cardToken) {
    var dataKey = transit.unwrap(stored.wrappedDataKey);
    try {
      return lastFourAndHashes(
          CardCrypto.decrypt(stored.panCiphertext, dataKey, cardToken),
          CardCrypto.decrypt(stored.securityCodeCiphertext, dataKey, cardToken));
    } finally {
      Arrays.fill(dataKey, (byte) 0);
    }
  }

  private IssuedCard lastFourAndHashes(String pan, String securityCode) {
    return new IssuedCard(
        pan.substring(pan.length() - 4), hashing.hash(pan), hashing.hash(securityCode));
  }

  // The primary key and the unique PAN hash both make `on conflict do nothing` insert no row:
  // the caller tells the two apart.
  private boolean insert(
      String cardToken,
      String pan,
      String panHash,
      String securityCode,
      Transit.DataKey dataKey,
      Instant expiresAt) {
    return jdbc.sql(
                """
                insert into card
                       (card_token, pan_hash, pan_ciphertext, security_code_ciphertext,
                        wrapped_data_key, expires_at)
                values (:cardToken, :panHash, :panCiphertext, :securityCodeCiphertext,
                        :wrappedDataKey, :expiresAt)
                on conflict do nothing
                """)
            .param("cardToken", cardToken)
            .param("panHash", Base64.getDecoder().decode(panHash))
            .param("panCiphertext", CardCrypto.encrypt(pan, dataKey.plaintext(), cardToken))
            .param(
                "securityCodeCiphertext",
                CardCrypto.encrypt(securityCode, dataKey.plaintext(), cardToken))
            .param("wrappedDataKey", dataKey.wrapped())
            .param("expiresAt", expiresAt.atOffset(ZoneOffset.UTC))
            .update()
        == 1;
  }
}
