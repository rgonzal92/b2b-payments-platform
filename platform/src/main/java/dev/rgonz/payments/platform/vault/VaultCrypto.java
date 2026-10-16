package dev.rgonz.payments.platform.vault;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts card data with AES-256-GCM and hashes PANs with HMAC-SHA-256, under two separate 256-bit
 * keys from configuration (ADR-0004). A missing or wrong-length key stops the platform at startup.
 * Neither the keys nor what they protect are ever logged or put in an exception message.
 */
@Component
final class VaultCrypto {

  private static final String CIPHER = "AES/GCM/NoPadding";
  private static final String HMAC = "HmacSHA256";
  private static final int KEY_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec encryptionKey;
  private final SecretKeySpec hashKey;
  private final SecureRandom random = new SecureRandom();

  VaultCrypto(
      @Value("${vault.encryption-key:}") String encryptionKey,
      @Value("${vault.hash-key:}") String hashKey) {
    this.encryptionKey = new SecretKeySpec(decodeKey("vault.encryption-key", encryptionKey), "AES");
    this.hashKey = new SecretKeySpec(decodeKey("vault.hash-key", hashKey), HMAC);
  }

  /**
   * Encrypts under a fresh random nonce, with the Card Token as associated data: the result
   * decrypts only with that same Card Token, so it can't be moved to another card's row. Returns
   * the nonce followed by the ciphertext and its authentication tag.
   */
  byte[] encrypt(String plaintext, String cardToken) {
    var nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      var cipher = Cipher.getInstance(CIPHER);
      cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(cardToken.getBytes(StandardCharsets.UTF_8));
      var ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.allocate(NONCE_BYTES + ciphertext.length)
          .put(nonce)
          .put(ciphertext)
          .array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Encrypting card data failed.", e);
    }
  }

  /**
   * Decrypts what {@link #encrypt} returned for this Card Token; throws when the data was changed
   * or belongs to another Card Token, since its authentication tag no longer matches.
   */
  String decrypt(byte[] sealed, String cardToken) {
    try {
      var cipher = Cipher.getInstance(CIPHER);
      cipher.init(
          Cipher.DECRYPT_MODE,
          encryptionKey,
          new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES));
      cipher.updateAAD(cardToken.getBytes(StandardCharsets.UTF_8));
      var plaintext = cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Card data failed to decrypt or was tampered with.", e);
    }
  }

  /**
   * The PAN's keyed hash: the same PAN always gives the same hash, so the vault can find or reject
   * a PAN without decrypting anything, and without the hash key nobody can test guesses against it.
   */
  byte[] hash(String pan) {
    try {
      var mac = Mac.getInstance(HMAC);
      mac.init(hashKey);
      return mac.doFinal(pan.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Hashing a PAN failed.", e);
    }
  }

  // The messages name the property, never its value.
  private static byte[] decodeKey(String property, String base64) {
    if (base64 == null || base64.isBlank()) {
      throw new IllegalStateException(
          property + " is missing: run scripts/init-env.sh to add the vault's keys to .env.");
    }
    byte[] key;
    try {
      key = Base64.getDecoder().decode(base64.strip());
    } catch (IllegalArgumentException notBase64) {
      // Not chained: the decoder's message quotes a character of the key.
      throw new IllegalStateException(
          property + " isn't valid base64: generate it with openssl rand -base64 32.");
    }
    if (key.length != KEY_BYTES) {
      throw new IllegalStateException(
          property
              + " must be "
              + KEY_BYTES
              + " bytes (256 bits) once base64-decoded, but is "
              + key.length
              + ": generate it with openssl rand -base64 32.");
    }
    return key;
  }
}
