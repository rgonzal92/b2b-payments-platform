package dev.rgonz.payments.authorization.network;

import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.contracts.network.AuthorizationResponse;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc;
import dev.rgonz.payments.contracts.network.ReversalRequest;
import dev.rgonz.payments.contracts.network.ReversalResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * The Network link (ADR-0006), served by the service (ADR-0016): the Network asks for an
 * Authorization, or cancels one with a Reversal, over gRPC, signed in with its own token
 * (SecurityConfig). Spring gRPC documents nothing reactive, so this is the bridge to Reactor: each
 * call builds its {@link Mono}, subscribes to it and returns at once, freeing its call thread
 * ({@link GrpcCallThreads}); the answer goes out from whichever thread completes the Mono, one of
 * R2DBC's event loop threads.
 *
 * <p>An Authorization goes through a time limiter and a circuit breaker around the database (#93),
 * both configured as {@value #DATABASE} in application.properties. Past the time limit, or while
 * the breaker is open, or on any error, the Network gets {@code 91} (Issuer unavailable) at once
 * instead of timing out and sending a Reversal. A {@code 91} leaves no approval standing: the time
 * limit cancels the Authorization's transaction, which rolls back unless its commit was already
 * under way, so the service then reverses the Authorization itself, in the background, as the
 * Network would have (M5 design, "The service's hot path"). A Reversal goes through the same time
 * limit, so the Network gets a quick error to resend it on instead of waiting for its deadline.
 */
@GrpcService
class IssuerAuthorizationService extends IssuerAuthorizationGrpc.IssuerAuthorizationImplBase {

  private static final Logger log = LoggerFactory.getLogger(IssuerAuthorizationService.class);
  // DE 39 on a Reversal's answer: the service has it, whatever it changed.
  private static final String ACKNOWLEDGED = "00";
  static final String DATABASE = "authorization-database";

  private final Authorizations authorizations;
  private final MeterRegistry meters;
  private final CircuitBreaker databaseBreaker;
  private final TimeLimiter databaseTimeLimit;
  private final Retry selfReversalRetry;
  private final Duration selfReversalGiveUpAfter;

  IssuerAuthorizationService(
      Authorizations authorizations,
      MeterRegistry meters,
      CircuitBreakerRegistry circuitBreakers,
      TimeLimiterRegistry timeLimiters,
      @Value("${authorization.self-reversal.first-backoff}") Duration selfReversalFirstBackoff,
      @Value("${authorization.self-reversal.max-backoff}") Duration selfReversalMaxBackoff,
      @Value("${authorization.self-reversal.give-up-after}") Duration selfReversalGiveUpAfter) {
    this.authorizations = authorizations;
    this.meters = meters;
    databaseBreaker = circuitBreakers.circuitBreaker(DATABASE);
    databaseTimeLimit = timeLimiters.timeLimiter(DATABASE);
    selfReversalRetry =
        Retry.backoff(Long.MAX_VALUE, selfReversalFirstBackoff).maxBackoff(selfReversalMaxBackoff);
    this.selfReversalGiveUpAfter = selfReversalGiveUpAfter;
    // One line per change of state, not per refused call: the runbook starts from these.
    databaseBreaker
        .getEventPublisher()
        .onStateTransition(
            event -> log.warn("Database circuit breaker: {}", event.getStateTransition()));
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
            // The limit inside the breaker, so a timeout counts as one of its failures.
            .transformDeferred(TimeLimiterOperator.of(databaseTimeLimit))
            .transformDeferred(CircuitBreakerOperator.of(databaseBreaker))
            .onErrorResume(error -> issuerUnavailable(requestId, error))
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
        reversal(requestId)
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
  // on the call: its answer is recorded, for a resend or a Reversal to find; only an
  // Authorization's own time limit cancels it. An error reaches the
  // Network as UNKNOWN with no description, as an exception thrown in a blocking handler would;
  // the log names the request ID only, and no message can hold a PAN or security code, which never
  // leave memory.
  private static <T> void answer(
      String call, String requestId, Mono<T> answer, StreamObserver<T> responseObserver) {
    var observer = (ServerCallStreamObserver<T>) responseObserver;
    // Without a handler, answering a call the Network cancelled would throw.
    observer.setOnCancelHandler(() -> log.info("{} {} cancelled by the Network", call, requestId));
    // contextCapture takes the call's trace, current on this thread, into the Mono's context, and
    // Reactor makes it current again on whichever thread runs each step, so the outbox row records
    // it (#96).
    answer
        .contextCapture()
        .subscribe(
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

  // A call the open breaker refused never reached the database, so it has nothing to reverse, and
  // isn't logged: every call is refused then, and the breaker's change of state says why. Logged by
  // request ID only, never the PAN.
  private Mono<Authorization> issuerUnavailable(String requestId, Throwable error) {
    if (!(error instanceof CallNotPermittedException)) {
      log.warn(
          "Authorization {} answered 91 and is being reversed: {}", requestId, error.toString());
      reverseInBackground(requestId);
    }
    return Mono.just(Authorization.declined(Authorization.ResponseCode.ISSUER_UNAVAILABLE));
  }

  // The Reversal the Network won't send after a 91: it reverses an approval whose commit went
  // through after all, or claims the request ID as reversed if nothing committed, so a late or
  // resent Authorization answers 05 (Authorizations.reverse). Retried with a growing pause while
  // the database is down, then given up and logged for the runbook. Held in memory only: a crash
  // loses it.
  private void reverseInBackground(String requestId) {
    reversal(requestId)
        .retryWhen(selfReversalRetry)
        .timeout(selfReversalGiveUpAfter)
        // In the trace of the call that answered 91.
        .contextCapture()
        .subscribe(
            null,
            error ->
                log.error(
                    "Authorization {} answered 91 and couldn't be reversed ({}): it may still be"
                        + " approved; see docs/runbooks/authorization-service-database-down.md",
                    requestId,
                    error.toString()));
  }

  private Mono<Void> reversal(String requestId) {
    return authorizations
        .reverse(requestId)
        .transformDeferred(TimeLimiterOperator.of(databaseTimeLimit));
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
