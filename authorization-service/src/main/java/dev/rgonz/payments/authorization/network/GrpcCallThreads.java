package dev.rgonz.payments.authorization.network;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerExecutorProvider;
import org.springframework.stereotype.Component;

/**
 * The threads gRPC runs each call on: its interceptors (the Network's token check) and the handler,
 * which only starts the call's Reactor pipeline and returns ({@link IssuerAuthorizationService}).
 * gRPC's default is an unbounded pool, where a handler that waited for the database would go
 * unnoticed, one new thread per waiting call; a small fixed pool keeps that mistake visible, and
 * {@code NetworkLinkTests} proves calls waiting on the database hold none of these threads. Never
 * Netty's event loop: a JWK set refresh blocks the token check while Keycloak answers.
 */
@Component
class GrpcCallThreads implements GrpcServerExecutorProvider, DisposableBean {

  static final int THREADS = 4;

  private final ExecutorService executor =
      Executors.newFixedThreadPool(THREADS, Thread.ofPlatform().name("grpc-call-", 1).factory());

  @Override
  public ExecutorService getExecutor() {
    return executor;
  }

  @Override
  public void destroy() {
    executor.shutdown();
  }
}
