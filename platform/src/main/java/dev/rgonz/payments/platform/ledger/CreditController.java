package dev.rgonz.payments.platform.ledger;

import dev.rgonz.payments.contracts.server.CreditApi;
import dev.rgonz.payments.contracts.server.model.CreditDto;
import dev.rgonz.payments.contracts.server.model.MoneyDto;
import dev.rgonz.payments.contracts.server.model.SignedMoneyDto;
import dev.rgonz.payments.platform.buyers.BuyerDirectory;
import dev.rgonz.payments.platform.shared.BuyerId;
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
class CreditController implements CreditApi {

  private final BuyerDirectory buyers;
  private final CreditLines creditLines;

  CreditController(BuyerDirectory buyers, CreditLines creditLines) {
    this.buyers = buyers;
    this.creditLines = creditLines;
  }

  @Override
  @PreAuthorize("hasRole('issuer-admin')")
  public ResponseEntity<MoneyDto> putCreditLine(UUID buyerId, MoneyDto creditLine) {
    var id = new BuyerId(buyerId);
    if (!buyers.exists(id)) {
      throw notFound("No Buyer has this ID.");
    }
    var created = creditLines.set(id, toMoney(creditLine));
    return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(creditLine);
  }

  @Override
  @PreAuthorize("hasRole('issuer-admin')")
  public ResponseEntity<CreditDto> getCredit(UUID buyerId) {
    // An unknown Buyer has no Credit Line either, so one lookup answers both.
    var credit =
        creditLines
            .findCredit(new BuyerId(buyerId))
            .orElseThrow(() -> notFound("This Buyer has no Credit Line."));
    return ResponseEntity.ok(
        new CreditDto()
            .creditLine(toDto(credit.creditLine()))
            .availableCredit(toSignedDto(credit.availableCredit())));
  }

  private static ErrorResponseException notFound(String detail) {
    return new ErrorResponseException(
        HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail), null);
  }

  private static Money toMoney(MoneyDto money) {
    return new Money(money.getMinorUnits(), Currency.getInstance(money.getCurrency().getValue()));
  }

  private static MoneyDto toDto(Money money) {
    return new MoneyDto()
        .minorUnits(money.minorUnits())
        .currency(MoneyDto.CurrencyEnum.fromValue(money.currency().getCurrencyCode()));
  }

  private static SignedMoneyDto toSignedDto(Money money) {
    return new SignedMoneyDto()
        .minorUnits(money.minorUnits())
        .currency(SignedMoneyDto.CurrencyEnum.fromValue(money.currency().getCurrencyCode()));
  }
}
