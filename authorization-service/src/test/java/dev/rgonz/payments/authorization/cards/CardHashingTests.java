package dev.rgonz.payments.authorization.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The service's keyed hash, which must equal the platform vault's for the same PAN or security code
 * under the same {@code vault.hash-key}, or no Charge would ever find its card.
 */
class CardHashingTests {

  // config/application.properties' test-only key: every byte 0x02.
  private static final String TEST_KEY = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=";

  @Test
  void hashesAsTheVaultDoesWithHmacSha256UnderTheHashKeyBase64Encoded() {
    // openssl dgst -sha256 -mac HMAC -macopt hexkey:0202...02 (32 bytes), base64-encoded.
    assertThat(new CardHashing(TEST_KEY).hash("4000001234567899"))
        .isEqualTo("6zyrUT9DOiqoK9Oi9SxBGHEa9nFgk/Txd2o3x5sAtiw=");
  }

  @Test
  void aMissingKeyStopsTheService() {
    assertThatThrownBy(() -> new CardHashing(""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("vault.hash-key")
        .hasMessageContaining("missing");
  }

  @Test
  void aKeyThatIsNot256BitsStopsTheServiceWithoutShowingIt() {
    var shortKey = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgIC";
    assertThatThrownBy(() -> new CardHashing(shortKey))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("vault.hash-key")
        .hasMessageContaining("32 bytes")
        .hasMessageNotContaining(shortKey);
  }

  @Test
  void aKeyThatIsNotBase64StopsTheServiceWithoutShowingIt() {
    assertThatThrownBy(() -> new CardHashing("not base64!"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("vault.hash-key")
        .hasMessageNotContaining("not base64!")
        .hasNoCause();
  }
}
