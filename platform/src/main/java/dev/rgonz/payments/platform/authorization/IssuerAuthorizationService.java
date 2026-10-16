package dev.rgonz.payments.platform.authorization;

import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.contracts.network.AuthorizationResponse;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.Objects;
import org.springframework.grpc.server.service.GrpcService;

/**
 * The Network link (ADR-0006): the Network asks for an Authorization over gRPC, signed in with its
 * own token (SecurityConfig). Reverse isn't overridden yet, so it answers UNIMPLEMENTED.
 */
@GrpcService
class IssuerAuthorizationService extends IssuerAuthorizationGrpc.IssuerAuthorizationImplBase {

  private final Authorizations authorizations;

  IssuerAuthorizationService(Authorizations authorizations) {
    this.authorizations = authorizations;
  }

  @Override
  public void authorize(
      AuthorizationRequest request, StreamObserver<AuthorizationResponse> responseObserver) {
    // Without a request ID there is nothing to record the answer under, or to answer a resend with.
    if (request.getRetrievalReferenceNumber().isBlank()) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("retrieval_reference_number (DE 37) is required.")
              .asRuntimeException());
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
    responseObserver.onNext(
        AuthorizationResponse.newBuilder()
            .setRetrievalReferenceNumber(request.getRetrievalReferenceNumber())
            .setResponseCode(authorization.responseCode().code())
            .setAuthorizationCode(Objects.requireNonNullElse(authorization.authorizationCode(), ""))
            .build());
    responseObserver.onCompleted();
  }
}
