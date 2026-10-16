package dev.rgonz.payments.simulator;

import com.google.protobuf.Timestamp;
import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.contracts.network.AuthorizationResponse;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc.IssuerAuthorizationBlockingStub;
import dev.rgonz.payments.contracts.network.ReversalRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;

/**
 * The card Network: carries an Acquirer's Authorization request to the Issuer's platform over gRPC,
 * signed in as the network client, and brings back the Issuer's answer. It doesn't wait on a slow
 * Issuer: when the platform doesn't answer within the deadline, or can't be reached, the Network
 * declines the Charge itself with {@code 91} (issuer unavailable) and sends a Reversal, so the
 * platform cancels any Authorization it approves too late. Never logs a request: a protobuf
 * message's {@code toString} prints every field, the PAN and security code included.
 */
@Component
class Network {

  // DE 39: the decline the Network answers for an Issuer it couldn't get an answer from in time.
  static final String ISSUER_UNAVAILABLE = "91";

  private static final Logger log = LoggerFactory.getLogger(Network.class);

  private final IssuerAuthorizationBlockingStub issuer;
  private final Duration deadline;
  private final AtomicInteger traceNumbers = new AtomicInteger();
  // Four attempts in all, 100, 200 and 400 ms apart.
  private final RetryTemplate reversals =
      new RetryTemplate(
          RetryPolicy.builder().maxRetries(3).delay(Duration.ofMillis(100)).multiplier(2).build());

  Network(
      IssuerAuthorizationBlockingStub issuer,
      @Value("${simulator.network.authorization-deadline}") Duration deadline) {
    this.issuer = issuer;
    this.deadline = deadline;
  }

  /**
   * Asks the Issuer to approve or decline the Charge the request describes. If the Issuer doesn't
   * answer within the deadline or can't be reached, declines it {@code 91} and reverses it.
   */
  AuthorizationResponse authorize(AuthorizationRequest request) {
    try {
      return issuer.withDeadlineAfter(deadline).authorize(request);
    } catch (StatusRuntimeException failed) {
      var status = failed.getStatus().getCode();
      if (status != Status.Code.DEADLINE_EXCEEDED && status != Status.Code.UNAVAILABLE) {
        throw failed;
      }
      var requestId = request.getRetrievalReferenceNumber();
      log.warn(
          "The Network got no answer to Authorization {} ({}): declined it {} and is reversing it",
          requestId,
          status,
          ISSUER_UNAVAILABLE);
      reverse(requestId);
      return AuthorizationResponse.newBuilder()
          .setRetrievalReferenceNumber(requestId)
          .setResponseCode(ISSUER_UNAVAILABLE)
          .build();
    }
  }

  // Sends the Reversal, each attempt with its own deadline. A Reversal is safe to repeat (the
  // platform reverses an Authorization once), so a failed one is sent again; when every attempt
  // fails, the log names the request ID for someone to follow up, and the Charge's decline stands.
  private void reverse(String requestId) {
    var now = Instant.now();
    var reversal =
        ReversalRequest.newBuilder()
            .setRetrievalReferenceNumber(requestId)
            .setTransmittedAt(
                Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()))
            .setSystemTraceAuditNumber(nextTraceNumber())
            .build();
    try {
      reversals.execute(() -> issuer.withDeadlineAfter(deadline).reverse(reversal));
    } catch (RetryException exhausted) {
      log.error(
          "The Network couldn't reverse Authorization {} after {} attempts ({}): the platform may"
              + " still hold an approval for it; see docs/runbooks/network-cant-reach-platform.md",
          requestId,
          exhausted.getExceptions().size(),
          Status.fromThrowable(exhausted.getLastException()).getCode());
    }
  }

  // DE 11: counts the messages the Network sends on its own, its Reversals, from 000001, starting
  // over after 999999.
  private String nextTraceNumber() {
    return "%06d".formatted(Math.floorMod(traceNumbers.getAndIncrement(), 999_999) + 1);
  }
}
