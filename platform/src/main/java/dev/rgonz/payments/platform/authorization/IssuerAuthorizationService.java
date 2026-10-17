package dev.rgonz.payments.platform.authorization;

import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.contracts.network.AuthorizationResponse;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc;
import dev.rgonz.payments.contracts.network.ReversalRequest;
import dev.rgonz.payments.contracts.network.ReversalResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Objects;
import org.springframework.grpc.server.service.GrpcService;

/**
 * The Network link (ADR-0006): the Network asks for an Authorization, or cancels one with a
 * Reversal, over gRPC, signed in with its own token (SecurityConfig).
 */
@GrpcService
class IssuerAuthorizationService extends IssuerAuthorizationGrpc.IssuerAuthorizationImplBase {

  // DE 39 on a Reversal's answer: the platform has it, whatever it changed.
  private static final String ACKNOWLEDGED = "00";

  private final Authorizations authorizations;
  private final MeterRegistry meters;

  IssuerAuthorizationService(Authorizations authorizations, MeterRegistry meters) {
    this.authorizations = authorizations;
    this.meters = meters;
    // Each series exists from startup at zero: Prometheus's rate() and increase() need a sample
    // before the first increment, or they miss it.
    for (var responseCode : Authorization.ResponseCode.values()) {
      answers(responseCode);
    }
  }

  @Override
  public void authorize(
      AuthorizationRequest request, StreamObserver<AuthorizationResponse> responseObserver) {
    // Without a request ID there is nothing to record the answer under, or to answer a resend with.
    if (request.getRetrievalReferenceNumber().isBlank()) {
      responseObserver.onError(missingRetrievalReferenceNumber());
      return;
    }
    var authorization =
        authorizations.authorize(
            new Charge(
                request.getRetrievalReferenceNumber(),
                request.getPrimaryAccountNumber(),
                request.getSecurityCode(),
                request.getExpirationDate(),
                request.getAmountMinor(),
                request.getCurrencyCode(),
                request.getCardAcceptorId()),
            Instant.now());
    // Every answer the Network gets, a resend's included, by DE 39: never the PAN or Card Token.
    answers(authorization.responseCode()).increment();
    responseObserver.onNext(
        AuthorizationResponse.newBuilder()
            .setRetrievalReferenceNumber(request.getRetrievalReferenceNumber())
            .setResponseCode(authorization.responseCode().code())
            .setAuthorizationCode(Objects.requireNonNullElse(authorization.authorizationCode(), ""))
            .build());
    responseObserver.onCompleted();
  }

  @Override
  public void reverse(ReversalRequest request, StreamObserver<ReversalResponse> responseObserver) {
    // Without a request ID there is no Authorization to cancel, or to record as reversed.
    if (request.getRetrievalReferenceNumber().isBlank()) {
      responseObserver.onError(missingRetrievalReferenceNumber());
      return;
    }
    authorizations.reverse(request.getRetrievalReferenceNumber());
    responseObserver.onNext(
        ReversalResponse.newBuilder()
            .setRetrievalReferenceNumber(request.getRetrievalReferenceNumber())
            .setResponseCode(ACKNOWLEDGED)
            .build());
    responseObserver.onCompleted();
  }

  private Counter answers(Authorization.ResponseCode responseCode) {
    return Counter.builder("authorization.answers")
        .description("Authorization answers sent to the Network, by response code (DE 39)")
        .tag("response.code", responseCode.code())
        .register(meters);
  }

  private static StatusRuntimeException missingRetrievalReferenceNumber() {
    return Status.INVALID_ARGUMENT
        .withDescription("retrieval_reference_number (DE 37) is required.")
        .asRuntimeException();
  }
}
