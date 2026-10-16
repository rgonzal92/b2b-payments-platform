package dev.rgonz.payments.platform.suppliers;

import dev.rgonz.payments.platform.shared.SecretKeys;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Works out each Remittance Advice's link token from its Payment, under a 256-bit key from
 * configuration, and hashes tokens for storage. A missing or wrong-length key stops the platform at
 * startup. Tokens are credentials: never logged or put in an exception message.
 */
@Component
final class LinkTokens {

  private static final String HMAC = "HmacSHA256";

  private final SecretKeySpec key;

  LinkTokens(@Value("${remittance.link-key:}") String key) {
    this.key = new SecretKeySpec(SecretKeys.decode256("remittance.link-key", key), HMAC);
  }

  /**
   * HMAC-SHA-256 of the Payment's ID, base64url-encoded: 256 bits nobody can work out without the
   * key, and the same every time for the same Payment, so every delivery attempt sends the same
   * link whether or not an earlier attempt's record survived.
   */
  String tokenFor(UUID paymentId) {
    try {
      var mac = Mac.getInstance(HMAC);
      mac.init(key);
      var token = mac.doFinal(paymentId.toString().getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Working out a link token failed.", e);
    }
  }

  /**
   * SHA-256 of the token, which is all that is stored: a token has 256 random-looking bits, so no
   * key is needed to stop anyone guessing one from its hash.
   */
  static byte[] hash(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Hashing a link token failed.", e);
    }
  }
}
