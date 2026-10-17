package dev.rgonz.payments.platform.reconciliation;

import dev.rgonz.payments.contracts.server.BreaksApi;
import dev.rgonz.payments.contracts.server.model.BreakDto;
import dev.rgonz.payments.contracts.server.model.BreakGapDto;
import dev.rgonz.payments.contracts.server.model.BreakKindDto;
import dev.rgonz.payments.contracts.server.model.BreakListDto;
import dev.rgonz.payments.contracts.server.model.BreakStatusDto;
import dev.rgonz.payments.platform.shared.BuyerId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestController;

/** Reconcilers and Issuer staff read the Breaks, each with the facts of the records it names. */
@RestController
class BreaksController implements BreaksApi {

  private final Breaks breaks;

  BreaksController(Breaks breaks) {
    this.breaks = breaks;
  }

  @Override
  @PreAuthorize("hasAnyRole('reconciler', 'issuer-admin')")
  public ResponseEntity<BreakListDto> listBreaks(
      BreakStatusDto status, BreakKindDto kind, UUID buyerId, String cursor, Integer limit) {
    var filter =
        new Breaks.Filter(
            status == null ? null : status.getValue(),
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
        .map(raised -> ResponseEntity.ok(toDto(raised)))
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

  private static BreakDto toDto(Breaks.Raised raised) {
    var found = raised.found();
    return new BreakDto()
        .id(raised.id())
        .kind(BreakKindDto.fromValue(found.kind().name()))
        .status(BreakStatusDto.fromValue(raised.status()))
        .recordId(found.recordId())
        .businessDate(found.businessDate())
        .buyerId(found.buyerId() == null ? null : found.buyerId().value())
        .gap(
            new BreakGapDto()
                .minorUnits(found.gap().minorUnits())
                .currency(found.gap().currency().getCurrencyCode()))
        .facts(found.facts())
        .raisedAt(raised.raisedAt().atOffset(ZoneOffset.UTC));
  }

  private static ErrorResponseException problem(HttpStatus status, String detail) {
    return new ErrorResponseException(
        status, ProblemDetail.forStatusAndDetail(status, detail), null);
  }
}
