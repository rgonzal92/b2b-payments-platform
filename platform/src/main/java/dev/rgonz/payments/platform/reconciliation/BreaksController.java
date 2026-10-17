package dev.rgonz.payments.platform.reconciliation;

import dev.rgonz.payments.contracts.server.BreaksApi;
import dev.rgonz.payments.contracts.server.model.AmountDto;
import dev.rgonz.payments.contracts.server.model.BreakDto;
import dev.rgonz.payments.contracts.server.model.BreakKindDto;
import dev.rgonz.payments.contracts.server.model.BreakListDto;
import dev.rgonz.payments.contracts.server.model.BreakResolutionDto;
import dev.rgonz.payments.contracts.server.model.BreakResolutionRequestDto;
import dev.rgonz.payments.contracts.server.model.BreakStatusDto;
import dev.rgonz.payments.contracts.server.model.LedgerAccountDto;
import dev.rgonz.payments.contracts.server.model.ResolutionKindDto;
import dev.rgonz.payments.platform.ledger.Account;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reconcilers and Issuer staff read the Breaks, each with the facts of the records it names; a
 * Reconciler resolves one.
 */
@RestController
class BreaksController implements BreaksApi {

  private final Breaks breaks;
  private final BreakResolutions resolutions;

  BreaksController(Breaks breaks, BreakResolutions resolutions) {
    this.breaks = breaks;
    this.resolutions = resolutions;
  }

  @Override
  @PreAuthorize("hasAnyRole('reconciler', 'issuer-admin')")
  public ResponseEntity<BreakListDto> listBreaks(
      BreakStatusDto status, BreakKindDto kind, UUID buyerId, String cursor, Integer limit) {
    var filter =
        new Breaks.Filter(
            status == null ? null : RaisedBreak.Status.valueOf(status.getValue()),
            kind == null ? null : BreakKind.valueOf(kind.getValue()),
            buyerId == null ? null : new BuyerId(buyerId));
    var page = breaks.list(filter, cursor == null ? null : position(cursor), limit);
    return ResponseEntity.ok(
        new BreakListDto()
            .items(page.breaks().stream().map(BreaksController::toDto).toList())
            .nextCursor(page.next() == null ? null : page.next().cursor()));
  }

  @Override
  @PreAuthorize("hasAnyRole('reconciler', 'issuer-admin')")
  public ResponseEntity<BreakDto> getBreak(UUID breakId) {
    return breaks
        .find(breakId)
        .map(found -> ResponseEntity.ok(toDto(found)))
        .orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "No Break has this ID."));
  }

  private static Breaks.Position position(String cursor) {
    try {
      return Breaks.Position.of(cursor);
    } catch (IllegalArgumentException e) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          "This cursor can't be read: pass a page's nextCursor as it is, or leave it out.");
    }
  }

  // Not issuer-admin: the role that sets Credit Lines and records Repayments doesn't also move
  // money out of Clearing Suspense or correct the books (M4 design).
  @Override
  @PreAuthorize("hasRole('reconciler')")
  public ResponseEntity<BreakDto> resolveBreak(UUID breakId, BreakResolutionRequestDto request) {
    var resolution = toResolution(request);
    return ResponseEntity.ok(toDto(resolutions.resolve(breakId, resolution, currentReconciler())));
  }

  private static Resolution toResolution(BreakResolutionRequestDto request) {
    // A field the kind doesn't take is a mistake to point out, not something to ignore.
    var accounts = request.getDebitAccount() != null || request.getCreditAccount() != null;
    var extra =
        switch (request.getKind()) {
          case ACCEPTED -> accounts || request.getBuyerId() != null || request.getAmount() != null;
          case REASSIGNED -> accounts;
          case CORRECTED -> request.getBuyerId() != null;
        };
    if (extra) {
      throw unprocessable(
          "An accepted Break takes only a note; a reassignment, a Buyer and an amount; a"
              + " correction, two Accounts and an amount.");
    }
    try {
      var note = request.getNote();
      return switch (request.getKind()) {
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
            .kind(BreakKindDto.fromValue(found.kind().name()))
            .recordId(found.recordId())
            .status(BreakStatusDto.fromValue(found.status().name()))
            .businessDate(found.businessDate())
            .buyerId(found.buyerId() == null ? null : found.buyerId().value())
            .gap(toDto(found.gap()))
            .facts(found.facts())
            .raisedAt(found.raisedAt().atOffset(ZoneOffset.UTC));
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
    return problem(HttpStatus.UNPROCESSABLE_CONTENT, detail);
  }

  private static ErrorResponseException problem(HttpStatus status, String detail) {
    return new ErrorResponseException(
        status, ProblemDetail.forStatusAndDetail(status, detail), null);
  }
}
