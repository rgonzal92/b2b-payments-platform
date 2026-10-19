package dev.rgonz.payments.authorization.cards;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Holds the platform's {@code vault.hash-key}, from the service's own environment, and hashes a PAN
 * or security code as the platform's vault does: HMAC-SHA-256, base64-encoded, so a request's hash
 * can be compared with the hashes in the copy of its card (ADR-0004, as amended). A missing or
 * wrong-length key stops the service at startup; neither the key nor what it hashes is ever logged
 * or put in an exception message.
 */
@Component
class CardHashing {

  private static final String PROPERTY = "vault.hash-key";
  private static final String HMAC = "HmacSHA256";
  private static final int KEY_BYTES = 32;

  private final SecretKeySpec key;

  CardHashing(@Value("${vault.hash-key:}") String hashKey) {
    this.key = new SecretKeySpec(decode(hashKey), HMAC);
  }

  String hash(String value) {
    try {
      var mac = Mac.getInstance(HMAC);
      mac.init(key);
      return Base64.getEncoder()
          .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Hashing with " + PROPERTY + " failed.", e);
    }
  }

  private static byte[] decode(String base64) {
    if (base64 == null || base64.isBlank()) {
      throw new IllegalStateException(
          PROPERTY + " is missing: run scripts/init-env.sh to add VAULT_HASH_KEY to .env.");
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(base64.strip());
    } catch (IllegalArgumentException notBase64) {
      // Not chained: the decoder's message quotes a character of the key.
      throw new IllegalStateException(PROPERTY + " isn't valid base64: use the platform's key.");
    }
    if (bytes.length != KEY_BYTES) {
      throw new IllegalStateException(
          PROPERTY
              + " must be "
              + KEY_BYTES
              + " bytes (256 bits) once base64-decoded, but is "
              + bytes.length
              + ": use the platform's key.");
    }
    return bytes;
  }
}
