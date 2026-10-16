package dev.rgonz.payments.platform.suppliers;

import dev.rgonz.payments.contracts.server.RemittanceLinksApi;
import dev.rgonz.payments.contracts.server.model.MoneyDto;
import dev.rgonz.payments.contracts.server.model.SingleUseCardDetailsDto;
import dev.rgonz.payments.platform.suppliers.RemittanceLinks.Opening;
import java.time.ZoneOffset;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestController;

/**
 * Opens Remittance Advice links, with no sign-in: the token is the credential. Never logs the token
 * or the card's details.
 */
@RestController
class RemittanceLinksController implements RemittanceLinksApi {

  private final RemittanceLinks links;

  RemittanceLinksController(RemittanceLinks links) {
    this.links = links;
  }

  @Override
  public ResponseEntity<SingleUseCardDetailsDto> openRemittanceLink(String token) {
    return switch (links.open(token)) {
      case Opening.Opened opened -> cardDetails(opened);
      case Opening.Gone _ ->
          throw problem(
              HttpStatus.GONE,
              "This link was already opened, has expired, or its card was cancelled.");
      case Opening.Unknown _ -> throw problem(HttpStatus.NOT_FOUND, "No such link.");
    };
  }

  private static ResponseEntity<SingleUseCardDetailsDto> cardDetails(Opening.Opened opened) {
    var card = opened.card();
    var amount = opened.amount();
    // No cache, shared or private, may keep the card's details.
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new SingleUseCardDetailsDto()
                .pan(opened.details().pan())
                .securityCode(opened.details().securityCode())
                .expiresAt(card.expiresAt().atOffset(ZoneOffset.UTC))
                .amount(
                    new MoneyDto()
                        .minorUnits(amount.minorUnits())
                        .currency(
                            MoneyDto.CurrencyEnum.fromValue(amount.currency().getCurrencyCode())))
                .invoiceNumber(opened.invoiceNumber()));
  }

  private static ErrorResponseException problem(HttpStatus status, String detail) {
    return new ErrorResponseException(
        status, ProblemDetail.forStatusAndDetail(status, detail), null);
  }
}
