package dev.rgonz.payments.gateway;

import java.net.ConnectException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Renders the errors the Gateway makes itself (no route for the path, the platform unreachable or
 * too slow) as RFC 9457 problem details, like every platform error. Runs before Spring Boot's
 * handler, which would answer its own JSON. The platform's own errors pass through untouched.
 */
@Component
@Order(-2)
class GatewayProblems implements ErrorWebExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GatewayProblems.class);

  private final ServerResponse.Context context;

  GatewayProblems(ServerCodecConfigurer codecs) {
    var writers = codecs.getWriters();
    this.context =
        new ServerResponse.Context() {
          @Override
          public List<HttpMessageWriter<?>> messageWriters() {
            return writers;
          }

          @Override
          public List<ViewResolver> viewResolvers() {
            return List.of();
          }
        };
  }

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
    if (exchange.getResponse().isCommitted()) {
      return Mono.error(error);
    }
    var problem = problemFor(error);
    if (problem.getStatus() >= 500) {
      log.warn(
          "{} {} answered {}",
          exchange.getRequest().getMethod(),
          exchange.getRequest().getPath(),
          problem.getStatus(),
          error);
    }
    return ServerResponse.status(problem.getStatus())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .bodyValue(problem)
        .flatMap(response -> response.writeTo(exchange, context));
  }

  // Only the status and a fixed detail: an exception's own message can name internal addresses.
  private static ProblemDetail problemFor(Throwable error) {
    if (refusedConnection(error)) {
      return ProblemDetail.forStatusAndDetail(
          HttpStatus.BAD_GATEWAY, "The platform can't be reached.");
    }
    if (error instanceof ErrorResponse response) {
      return ProblemDetail.forStatus(response.getStatusCode());
    }
    return ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  private static boolean refusedConnection(Throwable error) {
    for (var cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConnectException) {
        return true;
      }
    }
    return false;
  }
}
