package dev.rgonz.payments.platform.clearing;

import dev.rgonz.payments.platform.authorization.ApprovedAuthorizations;
import dev.rgonz.payments.platform.cards.Cards;
import dev.rgonz.payments.platform.cards.SingleUseCard;
import dev.rgonz.payments.platform.ledger.Holds;
import dev.rgonz.payments.platform.ledger.Journal;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import dev.rgonz.payments.platform.vault.Vault;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Each Clearing a file brought: posted to the journal, and recorded with how it matched its card's
 * Authorization ({@link ClearingOutcome}). Never stores the PAN: it goes to the vault, which
 * answers with the Card Token (ADR-0004).
 */
@Repository
class Clearings implements RecordedClearings {

  private final JdbcClient jdbc;
  private final Vault vault;
  private final Cards cards;
  private final ApprovedAuthorizations authorizations;
  private final Journal journal;
  private final Holds holds;

  Clearings(
      JdbcClient jdbc,
      Vault vault,
      Cards cards,
      ApprovedAuthorizations authorizations,
      Journal journal,
      Holds holds) {
    this.jdbc = jdbc;
    this.vault = vault;
    this.cards = cards;
    this.authorizations = authorizations;
    this.journal = journal;
    this.holds = holds;
  }

  /**
   * Imports one Clearing of the file. A card the vault knows moves to {@code CLEARED} whatever its
   * status, and so does its Hold if it is still open; the Payment is left alone. The Clearing's
   * outcome follows from what the import found ({@link ClearingOutcome.Facts}), and it is posted
   * either way, keyed by its Network record ID: to the card's Buyer Receivable, or to Clearing
   * Suspense when no Buyer can be charged for it. Then it is recorded with its outcome and Journal
   * Entry. Joins the caller's transaction, the import step's chunk, so all of it commits or rolls
   * back with the chunk. Returns false, and posts and records nothing, when its Network record ID
   * was already recorded, earlier in this file or in another: the record is skipped.
   */
  boolean importClearing(String fileId, ClearingRecord record) {
    var cardToken = vault.cardFor(record.pan()).orElse(null);
    var card = cardToken == null ? null : cards.find(cardToken).orElse(null);
    var authorization =
        card == null
            ? null
            : authorizations.find(cardToken, record.authorizationCode()).orElse(null);
    var holdReleased = false;
    if (card != null) {
      // The card first, then its Hold, in the order every module locks them, both locked until the
      // chunk commits. A Clearing of this card in another import waits, then finds this one
      // recorded; a CardExpired handled meanwhile waits, then finds the Hold cleared and leaves it.
      // A released Hold stays released.
      cards.clear(cardToken);
      // The Payment saga places each card's Hold under its Payment's ID.
      holdReleased = holds.isReleased(card.paymentId());
      holds.clear(card.paymentId());
    }
    var outcome =
        new ClearingOutcome.Facts(
                record.amount(),
                card == null ? null : card.amount().currency(),
                authorization,
                authorization != null && alreadyCleared(authorization.requestId()),
                holdReleased)
            .outcome();
    // To Clearing Suspense when no Buyer can be charged for it, as for every unknown card.
    var journalEntryId =
        card == null || outcome.postsToSuspense()
            ? journal.postClearingToSuspense(record.networkRecordId(), record.amount())
            : journal.postClearing(record.networkRecordId(), card.buyerId(), record.amount());
    // A record already recorded is skipped without a check before it, so two imports meeting one
    // record at once can't both record it: clearing the card and its Hold again changes nothing,
    // its posting returns the first one's entry (keyed by the Network record ID, posted once), and
    // its row goes nowhere (the primary key). Imports of one card take turns on the card's row.
    return jdbc.sql(
                """
                insert into clearing.clearing
                       (network_record_id, file_id, card_token, authorization_request_id,
                        cleared_amount_minor, cleared_currency, authorized_amount_minor,
                        authorized_currency, outcome, journal_entry_id)
                values (:networkRecordId, :fileId, :cardToken, :authorizationRequestId,
                        :clearedAmount, :clearedCurrency, :authorizedAmount, :authorizedCurrency,
                        :outcome, :journalEntryId)
                on conflict (network_record_id) do nothing
                """)
            .param("networkRecordId", record.networkRecordId())
            .param("fileId", fileId)
            .param("cardToken", cardToken)
            .param(
                "authorizationRequestId", authorization == null ? null : authorization.requestId())
            .param("clearedAmount", record.amount().minorUnits())
            .param("clearedCurrency", record.amount().currency().getCurrencyCode())
            .param(
                "authorizedAmount",
                authorization == null ? null : authorization.amount().minorUnits())
            .param(
                "authorizedCurrency",
                authorization == null ? null : authorization.amount().currency().getCurrencyCode())
            .param("outcome", outcome.name())
            .param("journalEntryId", journalEntryId)
            .update()
        == 1;
  }

  @Override
  public List<RecordedClearing> onBusinessDate(LocalDate businessDate) {
    // clearing keeps only each card's Card Token: its Buyer comes from cards, once per card.
    var buyers = new HashMap<String, Optional<BuyerId>>();
    return jdbc.sql(
            """
            select c.network_record_id, c.file_id, f.business_date, c.card_token,
                   c.authorization_request_id, c.cleared_amount_minor, c.cleared_currency,
                   c.authorized_amount_minor, c.authorized_currency, c.outcome
              from clearing.clearing c
              join clearing.clearing_file f on f.id = c.file_id
             where f.business_date = :businessDate
             order by c.network_record_id
            """)
        .param("businessDate", Date.valueOf(businessDate))
        .query(
            (row, rowNum) -> {
              var cardToken = row.getString("card_token");
              var authorizedCurrency = row.getString("authorized_currency");
              return new RecordedClearing(
                  row.getString("network_record_id"),
                  row.getString("file_id"),
                  row.getObject("business_date", LocalDate.class),
                  cardToken,
                  cardToken == null ? null : buyerOf(cardToken, buyers),
                  row.getString("authorization_request_id"),
                  new Money(
                      row.getLong("cleared_amount_minor"),
                      Currency.getInstance(row.getString("cleared_currency"))),
                  authorizedCurrency == null
                      ? null
                      : new Money(
                          row.getLong("authorized_amount_minor"),
                          Currency.getInstance(authorizedCurrency)),
                  ClearingOutcome.valueOf(row.getString("outcome")));
            })
        .list();
  }

  private BuyerId buyerOf(String cardToken, Map<String, Optional<BuyerId>> buyers) {
    return buyers
        .computeIfAbsent(cardToken, token -> cards.find(token).map(SingleUseCard::buyerId))
        .orElse(null);
  }

  // Whether a Clearing of this Authorization is recorded already, in this chunk or before it. The
  // literal matches clearing_authorization_first_idx's predicate, as a bound parameter wouldn't.
  private boolean alreadyCleared(String authorizationRequestId) {
    return jdbc.sql(
            """
            select exists (select 1 from clearing.clearing
                            where authorization_request_id = :authorizationRequestId
                              and outcome <> 'AUTHORIZATION_ALREADY_CLEARED')
            """)
        .param("authorizationRequestId", authorizationRequestId)
        .query(Boolean.class)
        .single();
  }
}
