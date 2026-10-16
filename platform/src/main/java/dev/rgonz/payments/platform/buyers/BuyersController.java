package dev.rgonz.payments.platform.buyers;

import dev.rgonz.payments.contracts.server.BuyersApi;
import dev.rgonz.payments.contracts.server.model.BuyerRequest;
import dev.rgonz.payments.platform.shared.Money;
import java.util.Currency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BuyersController implements BuyersApi {

  private final Buyers buyers;

  BuyersController(Buyers buyers) {
    this.buyers = buyers;
  }

  @Override
  @PreAuthorize("hasRole('issuer-admin')")
  public ResponseEntity<dev.rgonz.payments.contracts.server.model.Buyer> putBuyer(
      UUID buyerId, BuyerRequest request) {
    var threshold = request.getApprovalThreshold();
    var buyer =
        new Buyer(
            buyerId,
            request.getName(),
            new Money(
                threshold.getMinorUnits(),
                Currency.getInstance(threshold.getCurrency().getValue())));
    var status = buyers.save(buyer) ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status).body(toResponse(buyer));
  }

  private static dev.rgonz.payments.contracts.server.model.Buyer toResponse(Buyer buyer) {
    var threshold = buyer.approvalThreshold();
    return new dev.rgonz.payments.contracts.server.model.Buyer()
        .id(buyer.id())
        .name(buyer.name())
        .approvalThreshold(
            new dev.rgonz.payments.contracts.server.model.Money()
                .minorUnits(threshold.minorUnits())
                .currency(
                    dev.rgonz.payments.contracts.server.model.Money.CurrencyEnum.fromValue(
                        threshold.currency().getCurrencyCode())));
  }
}
