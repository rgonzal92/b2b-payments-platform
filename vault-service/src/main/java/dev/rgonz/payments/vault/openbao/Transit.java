package dev.rgonz.payments.vault.openbao;

import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * OpenBao's Transit engine over its HTTP API (docs/learning/openbao.md): a fresh data key per card,
 * and unwrapping a stored one, under the key-encryption key {@value #KEY}, which never leaves
 * OpenBao. The token's policy allows nothing else. Neither the token nor a data key is ever logged
 * or put in an exception message.
 */
@Component
public class Transit {

  /**
   * A data key: its 32 bytes, to encrypt with and then forget, and the same key wrapped by the
   * key-encryption key, to store. The wrapped key starts {@code vault:v<n>:}, n the version of the
   * key-encryption key that wrapped it. Never compared, so a record's equals comparing the key's
   * array by identity doesn't matter.
   */
  public record DataKey(byte[] plaintext, String wrapped) {}

  private record Response(Data data) {}

  private record Data(String plaintext, String ciphertext) {}

  // The key-encryption key: openbao/dev-init.hcl creates it, and the token's policy names it.
  private static final String KEY = "card-kek";

  private final RestClient openBao;

  Transit(
      RestClient.Builder builder,
      @Value("${openbao.address}") String address,
      @Value("${openbao.token:}") String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalStateException(
          "openbao.token is missing: run scripts/init-env.sh to add OPENBAO_TOKEN to .env.");
    }
    this.openBao =
        builder
            .baseUrl(address)
            .defaultHeader("X-Vault-Token", token.strip())
            .defaultStatusHandler(
                HttpStatusCode::is5xxServerError,
                (request, response) -> {
                  throw new OpenBaoUnavailableException(
                      "OpenBao answered " + response.getStatusCode() + ".", null);
                })
            .build();
  }

  /** A new 256-bit data key, from Transit's {@code datakey/plaintext}. */
  public DataKey newDataKey() {
    var data = post("/v1/transit/datakey/plaintext/{key}", Map.of("bits", 256));
    return new DataKey(Base64.getDecoder().decode(data.plaintext()), data.ciphertext());
  }

  /** The 32 bytes of a data key {@link #newDataKey} wrapped, from Transit's {@code decrypt}. */
  public byte[] unwrap(String wrappedDataKey) {
    var data = post("/v1/transit/decrypt/{key}", Map.of("ciphertext", wrappedDataKey));
    return Base64.getDecoder().decode(data.plaintext());
  }

  private Data post(String path, Map<String, Object> body) {
    try {
      return openBao.post().uri(path, KEY).body(body).retrieve().body(Response.class).data();
    } catch (ResourceAccessException unreachable) {
      throw new OpenBaoUnavailableException("OpenBao didn't answer.", unreachable);
    }
  }
}
