package dev.rgonz.payments.platform.vault;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Each card's PAN and security code, encrypted, with the PAN's keyed hash beside them. */
@Repository
class CardSecrets implements Vault {

  // A collision is one in a billion per card already issued; ten in a row means a bug, not chance.
  private static final int MAX_PAN_DRAWS = 10;

  private final JdbcClient jdbc;
  private final VaultCrypto crypto;
  private final RandomGenerator random;

  @Autowired
  CardSecrets(JdbcClient jdbc, VaultCrypto crypto) {
    this(jdbc, crypto, new SecureRandom());
  }

  // Tests pass their own draws, to force a PAN another card already has.
  CardSecrets(JdbcClient jdbc, VaultCrypto crypto, RandomGenerator random) {
    this.jdbc = jdbc;
    this.crypto = crypto;
    this.random = random;
  }

  @Override
  @Transactional
  public String generatePan(String cardToken) {
    var securityCode = Pans.newSecurityCode(random);
    for (var draw = 1; draw <= MAX_PAN_DRAWS; draw++) {
      var pan = Pans.newPan(random);
      if (insertUnlessPanTaken(cardToken, pan, securityCode)) {
        return pan.substring(pan.length() - 4);
      }
    }
    throw new IllegalStateException(
        "Drew " + MAX_PAN_DRAWS + " PANs that were all taken for card " + cardToken + ".");
  }

  @Override
  public CardDetails cardDetails(String cardToken) {
    return jdbc.sql(
            """
            select pan_ciphertext, security_code_ciphertext
              from vault.card_secret
             where card_token = :cardToken
            """)
        .param("cardToken", cardToken)
        .query(
            (row, rowNumber) ->
                new CardDetails(
                    crypto.decrypt(row.getBytes("pan_ciphertext"), cardToken),
                    crypto.decrypt(row.getBytes("security_code_ciphertext"), cardToken)))
        .optional()
        .orElseThrow(
            () -> new IllegalStateException("The vault has no card data for card " + cardToken));
  }

  @Override
  public Optional<String> cardFor(String pan) {
    return jdbc.sql("select card_token from vault.card_secret where pan_hash = :panHash")
        .param("panHash", crypto.hash(pan))
        .query(String.class)
        .optional();
  }

  // MessageDigest.isEqual takes as long whichever byte differs, so timing can't tell a caller how
  // much of a guess was right; it only shows whether the lengths differ.
  @Override
  public boolean securityCodeMatches(String cardToken, String securityCode) {
    return jdbc.sql(
            "select security_code_ciphertext from vault.card_secret where card_token = :cardToken")
        .param("cardToken", cardToken)
        .query((row, rowNumber) -> row.getBytes("security_code_ciphertext"))
        .optional()
        .map(
            storedCode ->
                MessageDigest.isEqual(
                    crypto.decrypt(storedCode, cardToken).getBytes(StandardCharsets.UTF_8),
                    securityCode.getBytes(StandardCharsets.UTF_8)))
        .orElse(false);
  }

  // The unique PAN hash rejects a PAN another card already has; `on conflict` reports that as no
  // row inserted instead of an error, which would abort the caller's transaction.
  private boolean insertUnlessPanTaken(String cardToken, String pan, String securityCode) {
    return jdbc.sql(
                """
                insert into vault.card_secret
                       (card_token, pan_hash, pan_ciphertext, security_code_ciphertext)
                values (:cardToken, :panHash, :panCiphertext, :securityCodeCiphertext)
                on conflict (pan_hash) do nothing
                """)
            .param("cardToken", cardToken)
            .param("panHash", crypto.hash(pan))
            .param("panCiphertext", crypto.encrypt(pan, cardToken))
            .param("securityCodeCiphertext", crypto.encrypt(securityCode, cardToken))
            .update()
        == 1;
  }
}
