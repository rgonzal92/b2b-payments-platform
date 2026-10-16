package dev.rgonz.payments.platform.shared;

import java.util.Base64;

/**
 * Reads a 256-bit secret key from configuration, base64-encoded, and refuses a missing or malformed
 * one so the platform fails at startup. The messages name the property, never its value.
 */
public final class SecretKeys {

  private static final int KEY_BYTES = 32;

  private SecretKeys() {}

  /** The key's 32 bytes; throws when it is missing, isn't base64 or isn't exactly 32 bytes. */
  public static byte[] decode256(String property, String base64) {
    if (base64 == null || base64.isBlank()) {
      throw new IllegalStateException(
          property + " is missing: run scripts/init-env.sh to add it to .env.");
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
