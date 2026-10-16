package dev.rgonz.payments.platform.authorization;

import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.contracts.network.AuthorizationResponse;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc;
import dev.rgonz.payments.platform.shared.Money;
import io.grpc.stub.StreamObserver;
import java.util.Currency;
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
    var authorization =
        authorizations.authorize(
            new Charge(
                request.getRetrievalReferenceNumber(),
                request.getPrimaryAccountNumber(),
                new Money(
                    request.getAmountMinor(), Currency.getInstance(request.getCurrencyCode())),
                request.getCardAcceptorId()));
    responseObserver.onNext(
        AuthorizationResponse.newBuilder()
            .setRetrievalReferenceNumber(request.getRetrievalReferenceNumber())
            .setResponseCode(authorization.responseCode().code())
            .setAuthorizationCode(Objects.requireNonNullElse(authorization.authorizationCode(), ""))
            .build());
    responseObserver.onCompleted();
  }
}
