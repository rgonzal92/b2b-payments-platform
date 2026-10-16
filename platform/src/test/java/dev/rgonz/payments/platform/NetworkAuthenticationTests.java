package dev.rgonz.payments.platform;

import static dev.rgonz.payments.platform.TestKeycloak.clientCredentialsToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc.IssuerAuthorizationBlockingStub;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.interceptor.security.BearerTokenAuthenticationInterceptor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Only the Network may call the Network link over gRPC: checked with real Keycloak tokens from the
 * repository's realm file, sent as gRPC metadata to the platform's gRPC server.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class NetworkAuthenticationTests {

  // Outside the platform's 990000 range, so no card ever has it: the answer is "14".
  private static final String UNKNOWN_PAN = "4000000000000002";

  @DynamicPropertySource
  static void issuer(DynamicPropertyRegistry registry) {
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestKeycloak::issuerUri);
  }

  @LocalGrpcServerPort int port;

  private ManagedChannel channel;

  @BeforeEach
  void connect() {
    channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
  }

  @AfterEach
  void disconnect() {
    channel.shutdownNow();
  }

  @Test
  void theNetworksTokenGetsAnAnswer() {
    var response = signedInWith(clientCredentialsToken("network")).authorize(chargeOn(UNKNOWN_PAN));

    assertThat(response.getResponseCode()).isEqualTo("14");
  }

  @Test
  void aCallWithoutATokenIsUnauthenticated() {
    var anonymous = stub();

    assertThat(statusOf(() -> anonymous.authorize(chargeOn(UNKNOWN_PAN))))
        .isEqualTo(Status.Code.UNAUTHENTICATED);
  }

  @Test
  void aTokenKeycloakDidntSignIsUnauthenticated() {
    var forged = signedInWith(forgedNetworkToken());

    assertThat(statusOf(() -> forged.authorize(chargeOn(UNKNOWN_PAN))))
        .isEqualTo(Status.Code.UNAUTHENTICATED);
  }

  @Test
  void anotherClientsTokenIsDenied() {
    var erp = signedInWith(clientCredentialsToken("acme-erp"));

    assertThat(statusOf(() -> erp.authorize(chargeOn(UNKNOWN_PAN))))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
  }

  private IssuerAuthorizationBlockingStub signedInWith(String token) {
    return stub().withInterceptors(new BearerTokenAuthenticationInterceptor(token));
  }

  private IssuerAuthorizationBlockingStub stub() {
    return IssuerAuthorizationGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
  }

  private static Status.Code statusOf(ThrowingCallable call) {
    return assertThatExceptionOfType(StatusRuntimeException.class)
        .isThrownBy(call)
        .actual()
        .getStatus()
        .getCode();
  }

  // Claims all the Network's token does, from the realm's issuer, but no key of the realm signed
  // it.
  // Built here rather than written out: a literal JWT is what secret scanners look for.
  private static String forgedNetworkToken() {
    var base64 = Base64.getUrlEncoder().withoutPadding();
    var header =
        """
        {"alg":"RS256","typ":"JWT"}""";
    var claims =
        """
        {"iss":"%s","sub":"service-account-network","roles":["network"],"exp":%d}"""
            .formatted(TestKeycloak.issuerUri(), Instant.now().plusSeconds(300).getEpochSecond());
    return String.join(
        ".",
        base64.encodeToString(header.getBytes(StandardCharsets.UTF_8)),
        base64.encodeToString(claims.getBytes(StandardCharsets.UTF_8)),
        base64.encodeToString("not a signature".getBytes(StandardCharsets.UTF_8)));
  }

  private static AuthorizationRequest chargeOn(String pan) {
    return AuthorizationRequest.newBuilder()
        .setPrimaryAccountNumber(pan)
        .setAmountMinor(1_240_000)
        .setCurrencyCode("USD")
        .setRetrievalReferenceNumber(UUID.randomUUID().toString())
        .setCardAcceptorId("GLOBEX-ACCEPTOR")
        .setSecurityCode("123")
        .build();
  }
}
