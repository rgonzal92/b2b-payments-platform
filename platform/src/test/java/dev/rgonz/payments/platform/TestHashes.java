package dev.rgonz.payments.platform;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The keyed hash a test expects for a PAN or security code, worked out with the standard library
 * rather than the vault's own hasher, under the test-only {@code vault.hash-key}.
 */
public final class TestHashes {

  private static final String TEST_HASH_KEY =
      // config/application.properties' test-only key: every byte 0x02.
      "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=";

  private TestHashes() {}

  public static String keyedHash(String value) {
    try {
      var key = new SecretKeySpec(Base64.getDecoder().decode(TEST_HASH_KEY), "HmacSHA256");
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return Base64.getEncoder()
          .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Hashing the test value failed.", e);
    }
  }
}
