package dev.rgonz.payments.vault.cards;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM under a card's data key, as the platform's vault encrypts under its one key: a fresh
 * random nonce each time, and the Card Token as associated data, so a ciphertext decrypts only on
 * its own card's row. Nothing it handles is ever logged or put in an exception message.
 */
final class CardCrypto {

  private static final String CIPHER = "AES/GCM/NoPadding";
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  private CardCrypto() {}

  /** The nonce followed by the ciphertext and its authentication tag. */
  static byte[] encrypt(String plaintext, byte[] dataKey, String cardToken) {
    var nonce = new byte[NONCE_BYTES];
    RANDOM.nextBytes(nonce);
    try {
      var cipher = Cipher.getInstance(CIPHER);
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(dataKey, "AES"),
          new GCMParameterSpec(TAG_BITS, nonce));
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
  static String decrypt(byte[] sealed, byte[] dataKey, String cardToken) {
    try {
      var cipher = Cipher.getInstance(CIPHER);
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(dataKey, "AES"),
          new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES));
      cipher.updateAAD(cardToken.getBytes(StandardCharsets.UTF_8));
      var plaintext = cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Card data failed to decrypt or was tampered with.", e);
    }
  }
}
