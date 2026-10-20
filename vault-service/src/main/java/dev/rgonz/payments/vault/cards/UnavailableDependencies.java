package dev.rgonz.payments.vault.cards;

import dev.rgonz.payments.vault.openbao.OpenBaoUnavailableException;
import io.grpc.Status;
import io.grpc.StatusException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.stereotype.Component;

/**
 * OpenBao or the database out of reach answers {@code UNAVAILABLE}, which tells the caller to wait
 * and try again (design Q23); any other failure stays Spring gRPC's {@code UNKNOWN}. The
 * description names the dependency only, never what the call carried.
 */
@Component
class UnavailableDependencies implements GrpcExceptionHandler {

  @Override
  public StatusException handleException(Throwable exception) {
    if (exception instanceof OpenBaoUnavailableException) {
      return Status.UNAVAILABLE.withDescription("OpenBao is unavailable.").asException();
    }
    if (exception instanceof DataAccessResourceFailureException) {
      return Status.UNAVAILABLE
          .withDescription("The Card Vault's database is unavailable.")
          .asException();
    }
    return null;
  }
}
