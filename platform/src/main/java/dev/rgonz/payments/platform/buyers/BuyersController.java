package dev.rgonz.payments.platform.buyers;

import dev.rgonz.payments.contracts.server.BuyersApi;
import dev.rgonz.payments.contracts.server.model.BuyerDto;
import dev.rgonz.payments.contracts.server.model.BuyerRequestDto;
import dev.rgonz.payments.contracts.server.model.MoneyDto;
import dev.rgonz.payments.platform.shared.Money;
import java.util.Currency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BuyersController implements BuyersApi {

  private final Buyers buyers;

  BuyersController(Buyers buyers) {
    this.buyers = buyers;
  }

  @Override
  @PreAuthorize("hasRole('issuer-admin')")
  public ResponseEntity<BuyerDto> putBuyer(UUID buyerId, BuyerRequestDto request) {
    var buyer = new Buyer(buyerId, request.getName(), toMoney(request.getApprovalThreshold()));
    if (buyers.insertIfAbsent(buyer)) {
      return ResponseEntity.status(HttpStatus.CREATED).body(toDto(buyer));
    }
    var existing = buyers.find(buyerId).orElseThrow();
    if (!existing.equals(buyer)) {
      throw new ErrorResponseException(
          HttpStatus.CONFLICT,
          ProblemDetail.forStatusAndDetail(
              HttpStatus.CONFLICT, "A different Buyer already has this ID."),
          null);
    }
    return ResponseEntity.ok(toDto(existing));
  }

  private static Money toMoney(MoneyDto money) {
    return new Money(money.getMinorUnits(), Currency.getInstance(money.getCurrency().getValue()));
  }

  private static BuyerDto toDto(Buyer buyer) {
    var threshold = buyer.approvalThreshold();
    return new BuyerDto()
        .id(buyer.id())
        .name(buyer.name())
        .approvalThreshold(
            new MoneyDto()
                .minorUnits(threshold.minorUnits())
                .currency(MoneyDto.CurrencyEnum.fromValue(threshold.currency().getCurrencyCode())));
  }
}
