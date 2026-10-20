package dev.rgonz.payments.vault.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.google.protobuf.Timestamp;
import dev.rgonz.payments.contracts.vault.CardVaultGrpc;
import dev.rgonz.payments.contracts.vault.CardVaultGrpc.CardVaultBlockingStub;
import dev.rgonz.payments.contracts.vault.IssueCardRequest;
import dev.rgonz.payments.vault.TestcontainersConfiguration;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;

/**
 * IssueCard over gRPC, on the service's own PostgreSQL and a dev-mode OpenBao: the test plays the
 * platform, then reads the stored row as an attacker with the database would, and unwraps it as
 * only someone holding OpenBao's key could.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IssueCardTests {

  // config/application.properties' test-only hash key: every byte 0x02.
  private static final byte[] TEST_HASH_KEY = filled((byte) 2);

  @LocalGrpcServerPort int grpcPort;
  @Autowired JdbcClient jdbc;
  @Autowired GenericContainer<?> openBaoContainer;

  ManagedChannel channel;
  CardVaultBlockingStub cardVault;

  @BeforeEach
  void connect() {
    channel = NettyChannelBuilder.forAddress("localhost", grpcPort).usePlaintext().build();
    cardVault = CardVaultGrpc.newBlockingStub(channel);
  }

  @AfterEach
  void disconnect() {
    channel.shutdownNow();
  }

  @Test
  void issuesACardWhoseHashesAreThoseOfItsPanAndSecurityCode() throws Exception {
    var cardToken = newCardToken();

    var issued = cardVault.issueCard(request(cardToken, Instant.now()));

    var card = decryptStoredCard(cardToken);
    assertThat(card.pan()).hasSize(16).startsWith("990000").endsWith(issued.getLastFour());
    assertThat(luhnValid(card.pan())).isTrue();
    assertThat(card.securityCode()).matches("\\d{3}");
    assertThat(issued.getPanHash()).isEqualTo(hmac(card.pan()));
    assertThat(issued.getSecurityCodeHash()).isEqualTo(hmac(card.securityCode()));
  }

  @Test
  void aRepeatReturnsTheSameCardAndStoresOneRow() {
    var cardToken = newCardToken();
    var expiresAt = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);

    var first = cardVault.issueCard(request(cardToken, expiresAt));
    var repeat = cardVault.issueCard(request(cardToken, expiresAt.plusSeconds(60)));

    assertThat(repeat).isEqualTo(first);
    assertThat(
            jdbc.sql("select count(*) from card where card_token = :cardToken")
                .param("cardToken", cardToken)
                .query(Long.class)
                .single())
        .isEqualTo(1);
    // The first call's expiry stands.
    assertThat(
            jdbc.sql("select expires_at from card where card_token = :cardToken")
                .param("cardToken", cardToken)
                .query(OffsetDateTime.class)
                .single()
                .toInstant())
        .isEqualTo(expiresAt);
  }

  @Test
  void theRowHoldsNoPanSecurityCodeOrDataKeyInPlaintext() throws Exception {
    var cardToken = newCardToken();
    cardVault.issueCard(request(cardToken, Instant.now()));

    var row = storedRow(cardToken);
    var card = decryptStoredCard(cardToken);

    // The data key is wrapped by OpenBao's key-encryption key, whose version is in the prefix.
    assertThat((String) row.get("wrapped_data_key")).startsWith("vault:v1:");
    var stored =
        concat(
            (byte[]) row.get("pan_hash"),
            (byte[]) row.get("pan_ciphertext"),
            (byte[]) row.get("security_code_ciphertext"),
            ((String) row.get("wrapped_data_key")).getBytes(StandardCharsets.UTF_8));
    for (var secret :
        new byte[][] {
          card.pan().getBytes(StandardCharsets.UTF_8),
          card.dataKey(),
          Base64.getEncoder().encode(card.dataKey()),
          HexFormat.of().formatHex(card.dataKey()).getBytes(StandardCharsets.UTF_8)
        }) {
      assertThat(indexOf(stored, secret)).isEqualTo(-1);
    }
    // Three digits turn up by chance in base64 now and then, so the security code is looked for in
    // the binary columns only.
    var binary =
        concat((byte[]) row.get("pan_ciphertext"), (byte[]) row.get("security_code_ciphertext"));
    assertThat(indexOf(binary, card.securityCode().getBytes(StandardCharsets.UTF_8))).isEqualTo(-1);
  }

  @Test
  void eachCardHasADataKeyOfItsOwn() throws Exception {
    var first = newCardToken();
    var second = newCardToken();
    cardVault.issueCard(request(first, Instant.now()));
    cardVault.issueCard(request(second, Instant.now()));

    assertThat(decryptStoredCard(first).dataKey())
        .isNotEqualTo(decryptStoredCard(second).dataKey());
  }

  @Test
  void aCiphertextDecryptsOnlyUnderItsOwnCardToken() {
    var cardToken = newCardToken();
    cardVault.issueCard(request(cardToken, Instant.now()));

    var row = storedRow(cardToken);
    var dataKey = unwrap((String) row.get("wrapped_data_key"));
    assertThatExceptionOfType(GeneralSecurityException.class)
        .isThrownBy(() -> decrypt((byte[]) row.get("pan_ciphertext"), dataKey, newCardToken()));
  }

  @Test
  void aRequestWithoutACardTokenOrExpiryIsInvalid() {
    var noToken = IssueCardRequest.newBuilder().setExpiresAt(timestamp(Instant.now())).build();
    var noExpiry = IssueCardRequest.newBuilder().setCardToken(newCardToken()).build();

    for (var invalid : new IssueCardRequest[] {noToken, noExpiry}) {
      assertThatExceptionOfType(StatusRuntimeException.class)
          .isThrownBy(() -> cardVault.issueCard(invalid))
          .extracting(e -> e.getStatus().getCode())
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
  }

  @Test
  void anOpenBaoThatDoesNotAnswerMakesIssueCardUnavailable() {
    var docker = openBaoContainer.getDockerClient();
    docker.pauseContainerCmd(openBaoContainer.getContainerId()).exec();
    try {
      // OpenBao accepts the connection but never answers: the read times out (5 s).
      assertThatExceptionOfType(StatusRuntimeException.class)
          .isThrownBy(() -> cardVault.issueCard(request(newCardToken(), Instant.now())))
          .extracting(e -> e.getStatus().getCode())
          .isEqualTo(Status.Code.UNAVAILABLE);
    } finally {
      docker.unpauseContainerCmd(openBaoContainer.getContainerId()).exec();
    }
  }

  private record Card(String pan, String securityCode, byte[] dataKey) {}

  private record TransitResponse(TransitData data) {}

  private record TransitData(String plaintext) {}

  private Card decryptStoredCard(String cardToken) throws GeneralSecurityException {
    var row = storedRow(cardToken);
    var dataKey = unwrap((String) row.get("wrapped_data_key"));
    var pan = decrypt((byte[]) row.get("pan_ciphertext"), dataKey, cardToken);
    assertThat(row.get("pan_hash")).isEqualTo(Base64.getDecoder().decode(hmac(pan)));
    return new Card(
        pan, decrypt((byte[]) row.get("security_code_ciphertext"), dataKey, cardToken), dataKey);
  }

  private Map<String, Object> storedRow(String cardToken) {
    return jdbc.sql("select * from card where card_token = :cardToken")
        .param("cardToken", cardToken)
        .query()
        .singleRow();
  }

  // Unwrapped with the root token, outside the service: what the rewrap and reveal will do later.
  private byte[] unwrap(String wrappedDataKey) {
    var response =
        RestClient.create()
            .post()
            .uri(
                "http://{host}:{port}/v1/transit/decrypt/card-kek",
                openBaoContainer.getHost(),
                openBaoContainer.getMappedPort(8200))
            .header("X-Vault-Token", TestcontainersConfiguration.ROOT_TOKEN)
            .body(Map.of("ciphertext", wrappedDataKey))
            .retrieve()
            .body(TransitResponse.class);
    return Base64.getDecoder().decode(response.data().plaintext());
  }

  // AES-256-GCM: a 12-byte nonce, then the ciphertext and its 16-byte tag; the Card Token as AAD.
  private static String decrypt(byte[] sealed, byte[] dataKey, String cardToken)
      throws GeneralSecurityException {
    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(dataKey, "AES"),
        new GCMParameterSpec(128, sealed, 0, 12));
    cipher.updateAAD(cardToken.getBytes(StandardCharsets.UTF_8));
    return new String(cipher.doFinal(sealed, 12, sealed.length - 12), StandardCharsets.UTF_8);
  }

  private static String hmac(String value) throws GeneralSecurityException {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(TEST_HASH_KEY, "HmacSHA256"));
    return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static boolean luhnValid(String digits) {
    var sum = 0;
    for (var i = 0; i < digits.length(); i++) {
      var digit = digits.charAt(digits.length() - 1 - i) - '0';
      if (i % 2 == 1) {
        digit = digit * 2 > 9 ? digit * 2 - 9 : digit * 2;
      }
      sum += digit;
    }
    return sum % 10 == 0;
  }

  private static byte[] concat(byte[]... parts) {
    var all = new ByteArrayOutputStream();
    for (var part : parts) {
      all.writeBytes(part);
    }
    return all.toByteArray();
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    for (var i = 0; i + needle.length <= haystack.length; i++) {
      if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
        return i;
      }
    }
    return -1;
  }

  private static IssueCardRequest request(String cardToken, Instant expiresAt) {
    return IssueCardRequest.newBuilder()
        .setCardToken(cardToken)
        .setExpiresAt(timestamp(expiresAt))
        .build();
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }

  private static String newCardToken() {
    return UUID.randomUUID().toString();
  }

  private static byte[] filled(byte value) {
    var bytes = new byte[32];
    Arrays.fill(bytes, value);
    return bytes;
  }
}
