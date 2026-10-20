package dev.rgonz.payments.vault.openbao;

import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * OpenBao's Transit engine over its HTTP API (docs/learning/openbao.md): a fresh data key per card,
 * and unwrapping a stored one, under the key-encryption key {@code openbao.transit-key}, which
 * never leaves OpenBao. The token's policy allows nothing else. Neither the token nor a data key is
 * ever logged or put in an exception message.
 */
@Component
public class Transit {

  /**
   * A data key: its 32 bytes, to encrypt with and then forget, and the same key wrapped by the
   * key-encryption key, to store. The wrapped key starts {@code vault:v<n>:}, n the version of the
   * key-encryption key that wrapped it. A class, not a record: a record's equals would compare the
   * key's array by identity.
   */
  public static final class DataKey {
    private final byte[] plaintext;
    private final String wrapped;

    DataKey(byte[] plaintext, String wrapped) {
      this.plaintext = plaintext;
      this.wrapped = wrapped;
    }

    public byte[] plaintext() {
      return plaintext;
    }

    public String wrapped() {
      return wrapped;
    }
  }

  private record Response(Data data) {}

  private record Data(String plaintext, String ciphertext) {}

  private final RestClient openBao;
  private final String key;

  Transit(
      RestClient.Builder builder,
      @Value("${openbao.address}") String address,
      @Value("${openbao.token:}") String token,
      @Value("${openbao.transit-key}") String key) {
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
    this.key = key;
  }

  /** A new 256-bit data key, from Transit's {@code datakey/plaintext}. */
  public DataKey newDataKey() {
    var data =
        call(
            () ->
                openBao
                    .post()
                    .uri("/v1/transit/datakey/plaintext/{key}", key)
                    .body(Map.of("bits", 256))
                    .retrieve()
                    .body(Response.class)
                    .data());
    return new DataKey(Base64.getDecoder().decode(data.plaintext()), data.ciphertext());
  }

  /** The 32 bytes of a data key {@link #newDataKey} wrapped, from Transit's {@code decrypt}. */
  public byte[] unwrap(String wrappedDataKey) {
    var data =
        call(
            () ->
                openBao
                    .post()
                    .uri("/v1/transit/decrypt/{key}", key)
                    .body(Map.of("ciphertext", wrappedDataKey))
                    .retrieve()
                    .body(Response.class)
                    .data());
    return Base64.getDecoder().decode(data.plaintext());
  }

  private static Data call(Supplier<Data> request) {
    try {
      return request.get();
    } catch (ResourceAccessException unreachable) {
      throw new OpenBaoUnavailableException("OpenBao didn't answer.", unreachable);
    }
  }
}
