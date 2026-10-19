package dev.rgonz.payments.authorization.network;

import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.contracts.network.AuthorizationResponse;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc;
import dev.rgonz.payments.contracts.network.ReversalRequest;
import dev.rgonz.payments.contracts.network.ReversalResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;

/**
 * The Network link (ADR-0006), served by the service (ADR-0016): the Network asks for an
 * Authorization, or cancels one with a Reversal, over gRPC, signed in with its own token
 * (SecurityConfig). Spring gRPC documents nothing reactive, so this is the bridge to Reactor: each
 * call builds its {@link Mono}, subscribes to it and returns at once, freeing its call thread
 * ({@link GrpcCallThreads}); the answer goes out from whichever thread completes the Mono, one of
 * R2DBC's event loop threads.
 */
@GrpcService
class IssuerAuthorizationService extends IssuerAuthorizationGrpc.IssuerAuthorizationImplBase {

  private static final Logger log = LoggerFactory.getLogger(IssuerAuthorizationService.class);
  // DE 39 on a Reversal's answer: the service has it, whatever it changed.
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
    var requestId = request.getRetrievalReferenceNumber();
    // Without a request ID there is nothing to record the answer under, or to answer a resend with.
    if (requestId.isBlank()) {
      responseObserver.onError(missingRetrievalReferenceNumber());
      return;
    }
    var charge =
        new Charge(
            requestId,
            request.getPrimaryAccountNumber(),
            request.getSecurityCode(),
            request.getExpirationDate(),
            request.getAmountMinor(),
            request.getCurrencyCode(),
            request.getCardAcceptorId());
    answer(
        "Authorization",
        requestId,
        authorizations
            .authorize(charge, Instant.now())
            .map(
                authorization -> {
                  // Every answer the Network gets, a resend's included, by DE 39: never the PAN or
                  // Card Token.
                  answers(authorization.responseCode()).increment();
                  return AuthorizationResponse.newBuilder()
                      .setRetrievalReferenceNumber(requestId)
                      .setResponseCode(authorization.responseCode().code())
                      .setAuthorizationCode(
                          Objects.requireNonNullElse(authorization.authorizationCode(), ""))
                      .build();
                }),
        responseObserver);
  }

  @Override
  public void reverse(ReversalRequest request, StreamObserver<ReversalResponse> responseObserver) {
    var requestId = request.getRetrievalReferenceNumber();
    // Without a request ID there is no Authorization to cancel, or to record as reversed.
    if (requestId.isBlank()) {
      responseObserver.onError(missingRetrievalReferenceNumber());
      return;
    }
    answer(
        "Reversal",
        requestId,
        authorizations
            .reverse(requestId)
            .then(
                Mono.fromSupplier(
                    () ->
                        ReversalResponse.newBuilder()
                            .setRetrievalReferenceNumber(requestId)
                            .setResponseCode(ACKNOWLEDGED)
                            .build())),
        responseObserver);
  }

  // Subscribes and returns without waiting. The work runs to its end even if the Network gives up
  // on the call: its answer is recorded, for a resend or a Reversal to find. An error reaches the
  // Network as UNKNOWN with no description, as an exception thrown in a blocking handler would;
  // the log names the request ID only, and no message can hold a PAN or security code, which never
  // leave memory.
  private static <T> void answer(
      String call, String requestId, Mono<T> answer, StreamObserver<T> responseObserver) {
    var observer = (ServerCallStreamObserver<T>) responseObserver;
    // Without a handler, answering a call the Network cancelled would throw.
    observer.setOnCancelHandler(() -> log.info("{} {} cancelled by the Network", call, requestId));
    answer.subscribe(
        response -> {
          if (!observer.isCancelled()) {
            observer.onNext(response);
            observer.onCompleted();
          }
        },
        error -> {
          log.error("{} {} failed", call, requestId, error);
          if (!observer.isCancelled()) {
            observer.onError(Status.UNKNOWN.asRuntimeException());
          }
        });
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
