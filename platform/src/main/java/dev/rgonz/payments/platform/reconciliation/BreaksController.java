package dev.rgonz.payments.platform.reconciliation;

import dev.rgonz.payments.contracts.server.BreaksApi;
import dev.rgonz.payments.contracts.server.model.AmountDto;
import dev.rgonz.payments.contracts.server.model.BreakDto;
import dev.rgonz.payments.contracts.server.model.BreakResolutionDto;
import dev.rgonz.payments.contracts.server.model.BreakResolutionRequestDto;
import dev.rgonz.payments.contracts.server.model.LedgerAccountDto;
import dev.rgonz.payments.contracts.server.model.ResolutionKindDto;
import dev.rgonz.payments.platform.buyers.BuyerDirectory;
import dev.rgonz.payments.platform.ledger.Account;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestController;

/** A Reconciler resolves a Break. */
@RestController
class BreaksController implements BreaksApi {

  private final BreakResolutions resolutions;
  private final BuyerDirectory buyers;

  BreaksController(BreakResolutions resolutions, BuyerDirectory buyers) {
    this.resolutions = resolutions;
    this.buyers = buyers;
  }

  // Not issuer-admin: the role that sets Credit Lines and records Repayments doesn't also move
  // money out of Clearing Suspense or correct the books (M4 design).
  @Override
  @PreAuthorize("hasRole('reconciler')")
  public ResponseEntity<BreakDto> resolveBreak(UUID breakId, BreakResolutionRequestDto request) {
    var resolution = toResolution(request);
    return ResponseEntity.ok(toDto(resolutions.resolve(breakId, resolution, currentReconciler())));
  }

  private Resolution toResolution(BreakResolutionRequestDto request) {
    Resolution resolution;
    try {
      var note = request.getNote();
      resolution =
          switch (request.getKind()) {
            case ACCEPTED -> Resolution.accepted(note);
            case REASSIGNED ->
                Resolution.reassigned(
                    note,
                    request.getBuyerId() == null ? null : new BuyerId(request.getBuyerId()),
                    toMoney(request.getAmount()));
            case CORRECTED ->
                Resolution.corrected(
                    note,
                    toAccount(request.getDebitAccount()),
                    toAccount(request.getCreditAccount()),
                    toMoney(request.getAmount()));
          };
    } catch (IllegalArgumentException invalid) {
      throw unprocessable(invalid.getMessage());
    }
    // A Buyer Receivable of no Buyer would carry money no one owes.
    if (Stream.of(resolution.debited(), resolution.credited())
        .anyMatch(
            account ->
                account != null
                    && account.buyerId() != null
                    && !buyers.exists(account.buyerId()))) {
      throw unprocessable("No Buyer has this ID.");
    }
    return resolution;
  }

  private static Account toAccount(LedgerAccountDto account) {
    if (account == null) {
      return null;
    }
    return new Account(
        Account.Kind.valueOf(account.getKind().getValue()),
        account.getBuyerId() == null ? null : new BuyerId(account.getBuyerId()));
  }

  private static Money toMoney(AmountDto amount) {
    if (amount == null) {
      return null;
    }
    Currency currency;
    try {
      currency = Currency.getInstance(amount.getCurrency());
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException(amount.getCurrency() + " isn't a currency.", unknown);
    }
    return new Money(amount.getMinorUnits(), currency);
  }

  // The subject is the Reconciler's stable ID; the user name only makes the record readable.
  private static Reconciler currentReconciler() {
    var token =
        ((JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication())
            .getToken();
    var name = token.getClaimAsString("preferred_username");
    return new Reconciler(token.getSubject(), name != null ? name : token.getSubject());
  }

  private static BreakDto toDto(RaisedBreak found) {
    var dto =
        new BreakDto()
            .id(found.id())
            .kind(BreakDto.KindEnum.fromValue(found.kind().name()))
            .recordId(found.recordId())
            .status(BreakDto.StatusEnum.fromValue(found.status().name()))
            .businessDate(found.businessDate())
            .buyerId(found.buyerId() == null ? null : found.buyerId().value())
            .gap(toDto(found.gap()));
    if (found.resolved() instanceof RaisedBreak.Resolved resolved) {
      dto.resolution(
          new BreakResolutionDto()
              .kind(ResolutionKindDto.fromValue(resolved.kind().name()))
              .note(resolved.note())
              .resolvedBy(resolved.resolvedBy())
              .resolvedByName(resolved.resolvedByName())
              .resolvedAt(resolved.resolvedAt().atOffset(ZoneOffset.UTC))
              .correctingEntryId(resolved.correctingEntryId()));
    }
    return dto;
  }

  private static AmountDto toDto(Money money) {
    return new AmountDto()
        .minorUnits(money.minorUnits())
        .currency(money.currency().getCurrencyCode());
  }

  private static ErrorResponseException unprocessable(String detail) {
    return new ErrorResponseException(
        HttpStatus.UNPROCESSABLE_CONTENT,
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, detail),
        null);
  }
}
