package dev.rgonz.payments.vault;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerExecutorProvider;
import org.springframework.stereotype.Component;

/**
 * Runs each gRPC call on a virtual thread of its own: IssueCard blocks on OpenBao and the database,
 * and {@code spring.threads.virtual.enabled} reaches Tomcat but not Spring gRPC's Netty server,
 * whose default is an unbounded pool of platform threads.
 */
@Component
class GrpcCallThreads implements GrpcServerExecutorProvider, DisposableBean {

  private final ExecutorService executor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("grpc-call-", 1).factory());

  @Override
  public Executor getExecutor() {
    return executor;
  }

  @Override
  public void destroy() {
    executor.shutdown();
  }
}
