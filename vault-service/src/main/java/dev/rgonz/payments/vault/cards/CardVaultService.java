package dev.rgonz.payments.vault.cards;

import dev.rgonz.payments.contracts.vault.CardVaultGrpc;
import dev.rgonz.payments.contracts.vault.IssueCardRequest;
import dev.rgonz.payments.contracts.vault.IssueCardResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import org.springframework.grpc.server.service.GrpcService;

/**
 * IssueCard over gRPC (contracts' card_vault.proto), each call on a virtual thread of its own
 * (GrpcCallThreads), so it simply blocks on OpenBao and the database. Failures reach the caller
 * through {@link UnavailableDependencies}.
 */
@GrpcService
class CardVaultService extends CardVaultGrpc.CardVaultImplBase {

  private final Cards cards;

  CardVaultService(Cards cards) {
    this.cards = cards;
  }

  @Override
  public void issueCard(IssueCardRequest request, StreamObserver<IssueCardResponse> response) {
    if (request.getCardToken().isBlank() || !request.hasExpiresAt()) {
      response.onError(
          Status.INVALID_ARGUMENT
              .withDescription("IssueCard needs a card_token and an expires_at.")
              .asRuntimeException());
      return;
    }
    var expiresAt =
        Instant.ofEpochSecond(
            request.getExpiresAt().getSeconds(), request.getExpiresAt().getNanos());
    var card = cards.issue(request.getCardToken(), expiresAt);
    response.onNext(
        IssueCardResponse.newBuilder()
            .setLastFour(card.lastFour())
            .setPanHash(card.panHash())
            .setSecurityCodeHash(card.securityCodeHash())
            .build());
    response.onCompleted();
  }
}
