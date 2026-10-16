package dev.rgonz.payments.simulator;

import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.contracts.network.AuthorizationResponse;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc;
import dev.rgonz.payments.contracts.network.ReversalRequest;
import dev.rgonz.payments.contracts.network.ReversalResponse;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plays the platform's {@code IssuerAuthorization} gRPC service on a free local port: records each
 * Authorization request and Reversal with the {@code authorization} metadata it came with, answers
 * an Authorization with the response code and authorization code it was told to, after the delay it
 * was told to, and acknowledges every Reversal with {@code 00}.
 */
final class StubIssuer {

  /** An Authorization request as the stub received it. */
  record Received(AuthorizationRequest request, String authorization) {}

  /** A Reversal as the stub received it. */
  record ReceivedReversal(ReversalRequest request, String authorization) {}

  private static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final Context.Key<String> CALLERS_AUTHORIZATION = Context.key("authorization");

  final List<Received> received = new CopyOnWriteArrayList<>();
  final List<ReceivedReversal> reversals = new CopyOnWriteArrayList<>();
  private final Server server;
  private volatile String responseCode = "00";
  private volatile String authorizationCode = "TEST01";
  private volatile Duration delay = Duration.ZERO;

  StubIssuer() {
    var service =
        new IssuerAuthorizationGrpc.IssuerAuthorizationImplBase() {
          @Override
          public void authorize(
              AuthorizationRequest request, StreamObserver<AuthorizationResponse> responses) {
            received.add(new Received(request, CALLERS_AUTHORIZATION.get()));
            if (!answerAfterDelay((ServerCallStreamObserver<AuthorizationResponse>) responses)) {
              return;
            }
            responses.onNext(
                AuthorizationResponse.newBuilder()
                    .setRetrievalReferenceNumber(request.getRetrievalReferenceNumber())
                    .setResponseCode(responseCode)
                    .setAuthorizationCode(authorizationCode)
                    .build());
            responses.onCompleted();
          }

          @Override
          public void reverse(ReversalRequest request, StreamObserver<ReversalResponse> responses) {
            reversals.add(new ReceivedReversal(request, CALLERS_AUTHORIZATION.get()));
            responses.onNext(
                ReversalResponse.newBuilder()
                    .setRetrievalReferenceNumber(request.getRetrievalReferenceNumber())
                    .setResponseCode("00")
                    .build());
            responses.onCompleted();
          }
        };
    // Hands the call's authorization metadata to the service, which records it with the request.
    var passAuthorizationOn =
        new ServerInterceptor() {
          @Override
          public <Q, R> ServerCall.Listener<Q> interceptCall(
              ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
            var context =
                Context.current().withValue(CALLERS_AUTHORIZATION, headers.get(AUTHORIZATION));
            return Contexts.interceptCall(context, call, headers, next);
          }
        };
    try {
      server =
          NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
              .addService(ServerInterceptors.intercept(service, passAuthorizationOn))
              .build()
              .start();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // Waits out the delay; false when the caller gave up meanwhile, so there's no one to answer.
  private boolean answerAfterDelay(ServerCallStreamObserver<?> responses) {
    try {
      Thread.sleep(delay);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
    return !responses.isCancelled();
  }

  /** The address to point the simulator's gRPC channel at. */
  String address() {
    return "localhost:" + server.getPort();
  }

  /** Answers every Authorization from now on with this response code and authorization code. */
  void answerWith(String responseCode, String authorizationCode) {
    this.responseCode = responseCode;
    this.authorizationCode = authorizationCode;
  }

  /** Answers every Authorization from now on only after this long. */
  void answerAfter(Duration delay) {
    this.delay = delay;
  }

  void reset() {
    received.clear();
    reversals.clear();
    answerWith("00", "TEST01");
    answerAfter(Duration.ZERO);
  }

  void stop() {
    server.shutdownNow();
  }
}
