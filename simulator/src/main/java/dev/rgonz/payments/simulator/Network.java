package dev.rgonz.payments.simulator;

import dev.rgonz.payments.contracts.network.AuthorizationRequest;
import dev.rgonz.payments.contracts.network.AuthorizationResponse;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc.IssuerAuthorizationBlockingStub;
import org.springframework.stereotype.Component;

/**
 * The card Network: carries an Acquirer's Authorization request to the Issuer's platform over gRPC,
 * signed in as the network client, and brings back the Issuer's answer. Never logs a request: a
 * protobuf message's {@code toString} prints every field, the PAN and security code included.
 */
@Component
class Network {

  private final IssuerAuthorizationBlockingStub issuer;

  Network(IssuerAuthorizationBlockingStub issuer) {
    this.issuer = issuer;
  }

  /** Asks the Issuer to approve or decline the Charge the request describes. */
  AuthorizationResponse authorize(AuthorizationRequest request) {
    return issuer.authorize(request);
  }
}
