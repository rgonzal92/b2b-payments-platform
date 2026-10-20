package dev.rgonz.payments.vault.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The service's keyed hash, which must equal authorization-service's {@code CardHashing} for the
 * same PAN or security code under the same key, or no Charge would ever find its card.
 */
class CardHashingTests {

  // config/application.properties' test-only key: every byte 0x02, as authorization-service's.
  private static final String TEST_KEY = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=";

  @Test
  void hashesAsAuthorizationServiceDoes() {
    // The vector from authorization-service's CardHashingTests, made with
    // openssl dgst -sha256 -mac HMAC -macopt hexkey:0202...02 (32 bytes), base64-encoded.
    assertThat(new CardHashing(TEST_KEY).hash("4000001234567899"))
        .isEqualTo("6zyrUT9DOiqoK9Oi9SxBGHEa9nFgk/Txd2o3x5sAtiw=");
  }

  @Test
  void aMissingKeyStopsTheService() {
    assertThatThrownBy(() -> new CardHashing(""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("card.hash-key")
        .hasMessageContaining("missing");
  }

  @Test
  void aKeyThatIsNot256BitsStopsTheServiceWithoutShowingIt() {
    var shortKey = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgIC";
    assertThatThrownBy(() -> new CardHashing(shortKey))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32 bytes")
        .hasMessageNotContaining(shortKey);
  }
}
