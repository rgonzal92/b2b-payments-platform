package dev.rgonz.payments.platform.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * The vault's cryptography: AES-256-GCM bound to the Card Token, and the keyed hash of each PAN.
 */
class VaultCryptoTests {

  private static final String PAN = "9900001234567893";
  private static final String CARD_TOKEN = "card-token-1";

  private final VaultCrypto crypto = new VaultCrypto(testKey(1, 32), testKey(2, 32));

  @Test
  void whatIsEncryptedDecryptsToTheSameValue() {
    var sealed = crypto.encrypt(PAN, CARD_TOKEN);

    assertThat(new String(sealed, StandardCharsets.UTF_8)).doesNotContain(PAN);
    assertThat(crypto.decrypt(sealed, CARD_TOKEN)).isEqualTo(PAN);
  }

  @Test
  void encryptingTheSameValueTwiceGivesDifferentCiphertexts() {
    // A fresh nonce each time: GCM under a repeated nonce leaks the XOR of the two plaintexts.
    assertThat(crypto.encrypt(PAN, CARD_TOKEN)).isNotEqualTo(crypto.encrypt(PAN, CARD_TOKEN));
  }

  @Test
  void aChangedCiphertextByteFailsToDecrypt() {
    var sealed = crypto.encrypt(PAN, CARD_TOKEN);
    // Past the 12-byte nonce, in the encrypted PAN itself.
    sealed[12] ^= 1;

    assertThatThrownBy(() -> crypto.decrypt(sealed, CARD_TOKEN))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aChangedAuthenticationTagByteFailsToDecrypt() {
    var sealed = crypto.encrypt(PAN, CARD_TOKEN);
    sealed[sealed.length - 1] ^= 1;

    assertThatThrownBy(() -> crypto.decrypt(sealed, CARD_TOKEN))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aCiphertextMovedToAnotherCardsTokenFailsToDecrypt() {
    var sealed = crypto.encrypt(PAN, CARD_TOKEN);

    assertThatThrownBy(() -> crypto.decrypt(sealed, "card-token-2"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aPanHashesTheSameEachTimeAndDifferentlyUnderAnotherHashKey() {
    var otherHashKey = new VaultCrypto(testKey(1, 32), testKey(3, 32));

    assertThat(crypto.hash(PAN)).isEqualTo(crypto.hash(PAN)).hasSize(32);
    assertThat(otherHashKey.hash(PAN)).isNotEqualTo(crypto.hash(PAN));
  }

  @Test
  void aMissingKeyIsRefused() {
    assertThatThrownBy(() -> new VaultCrypto("", testKey(2, 32)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("vault.encryption-key")
        .hasMessageContaining("missing");
    assertThatThrownBy(() -> new VaultCrypto(testKey(1, 32), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("vault.hash-key")
        .hasMessageContaining("missing");
  }

  @Test
  void aKeyThatIsNot256BitsIsRefused() {
    // The JDK would accept a 128-bit AES key without complaint.
    assertThatThrownBy(() -> new VaultCrypto(testKey(1, 16), testKey(2, 32)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("vault.encryption-key")
        .hasMessageContaining("32 bytes");
    assertThatThrownBy(() -> new VaultCrypto(testKey(1, 32), testKey(2, 31)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("vault.hash-key")
        .hasMessageContaining("32 bytes");
  }

  @Test
  void aKeyThatIsNotBase64IsRefused() {
    assertThatThrownBy(() -> new VaultCrypto("not base64!", testKey(2, 32)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("vault.encryption-key")
        .hasMessageContaining("base64");
  }

  // A test-only key: every byte the same, so it is obviously not a real secret.
  private static String testKey(int fill, int length) {
    var key = new byte[length];
    Arrays.fill(key, (byte) fill);
    return Base64.getEncoder().encodeToString(key);
  }
}
