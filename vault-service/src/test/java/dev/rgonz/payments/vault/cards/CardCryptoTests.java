package dev.rgonz.payments.vault.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** AES-256-GCM under a card's data key, bound to its Card Token. */
class CardCryptoTests {

  private static final String PAN = "9900001234567893";
  private static final String CARD_TOKEN = "card-token-1";

  // A test-only data key: every byte 0x01, so it is obviously not a real one.
  private static final byte[] DATA_KEY = filled((byte) 1);

  @Test
  void whatIsEncryptedDecryptsToTheSameValue() {
    var sealed = CardCrypto.encrypt(PAN, DATA_KEY, CARD_TOKEN);

    assertThat(CardCrypto.decrypt(sealed, DATA_KEY, CARD_TOKEN)).isEqualTo(PAN);
  }

  @Test
  void encryptingTheSameValueTwiceGivesDifferentCiphertexts() {
    // A fresh nonce each time: GCM under a repeated nonce leaks the XOR of the two plaintexts.
    assertThat(CardCrypto.encrypt(PAN, DATA_KEY, CARD_TOKEN))
        .isNotEqualTo(CardCrypto.encrypt(PAN, DATA_KEY, CARD_TOKEN));
  }

  @Test
  void aCiphertextMovedToAnotherCardsTokenFailsToDecrypt() {
    var sealed = CardCrypto.encrypt(PAN, DATA_KEY, CARD_TOKEN);

    assertThatThrownBy(() -> CardCrypto.decrypt(sealed, DATA_KEY, "card-token-2"))
        .isInstanceOf(IllegalStateException.class);
  }

  private static byte[] filled(byte value) {
    var bytes = new byte[32];
    Arrays.fill(bytes, value);
    return bytes;
  }
}
