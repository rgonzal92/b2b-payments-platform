package dev.rgonz.payments.platform.clearing;

import dev.rgonz.payments.platform.authorization.ApprovedAuthorization;
import dev.rgonz.payments.platform.authorization.ApprovedAuthorizations;
import dev.rgonz.payments.platform.cards.Cards;
import dev.rgonz.payments.platform.ledger.Holds;
import dev.rgonz.payments.platform.ledger.Journal;
import dev.rgonz.payments.platform.vault.Vault;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Each Clearing a file brought: matched to its card's approved Authorization, posted to the journal
 * when it matches, and recorded with its outcome. Never stores the PAN: it goes to the vault, which
 * answers with the Card Token (ADR-0004).
 */
@Repository
class Clearings {

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
   * Imports one Clearing of the file: records it, then, when it matches its card's approved
   * Authorization, posts it to the journal (debiting the card's Buyer Receivable, keyed by the
   * Network record ID), marks the card's Hold {@code CLEARED}, moves the card to {@code CLEARED}
   * and records it matched. Joins the caller's transaction, the import step's chunk, so all of it
   * commits or rolls back with the chunk. Returns false, and posts nothing, when its Network record
   * ID was already recorded, earlier in this file or in another: the record is skipped.
   */
  boolean importClearing(String fileId, ClearingRecord record) {
    var cardToken = vault.cardFor(record.pan()).orElse(null);
    var card = cardToken == null ? null : cards.find(cardToken).orElse(null);
    var authorization =
        card == null
            ? null
            : authorizations.find(cardToken, record.authorizationCode()).orElse(null);
    // Recorded before anything is posted, so the primary key decides between two records with one
    // Network record ID, even in two imports at once: the second waits for the first to commit,
    // then is skipped.
    if (!record(fileId, record, cardToken, authorization)) {
      return false;
    }
    // The conditional move to CLEARED decides between two Clearings for one card: only the first
    // finds it AUTHORIZED.
    if (card != null && matches(record, authorization) && cards.clear(cardToken)) {
      var journalEntryId =
          journal.postClearing(record.networkRecordId(), card.buyerId(), record.amount());
      // The Payment saga places each card's Hold under its Payment's ID.
      holds.clear(card.paymentId());
      jdbc.sql(
              """
              update clearing.clearing set outcome = 'MATCHED', journal_entry_id = :journalEntryId
               where network_record_id = :networkRecordId
              """)
          .param("journalEntryId", journalEntryId)
          .param("networkRecordId", record.networkRecordId())
          .update();
    }
    return true;
  }

  // Records the Clearing, UNMATCHED until it is posted; false when its Network record ID was
  // already recorded.
  private boolean record(
      String fileId, ClearingRecord record, String cardToken, ApprovedAuthorization authorization) {
    // shortcut: every Clearing off the matched path is recorded UNMATCHED and not posted; #51
    // replaces this with the M3 design's outcome table.
    return jdbc.sql(
                """
                insert into clearing.clearing
                       (network_record_id, file_id, card_token, authorization_request_id,
                        cleared_amount_minor, cleared_currency, authorized_amount_minor,
                        authorized_currency, outcome)
                values (:networkRecordId, :fileId, :cardToken, :authorizationRequestId,
                        :clearedAmount, :clearedCurrency, :authorizedAmount, :authorizedCurrency,
                        'UNMATCHED')
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
            .update()
        == 1;
  }

  // An approved Authorization, not reversed since, in the Clearing's currency and for at least its
  // amount: a Clearing below it frees the rest of the Hold.
  private static boolean matches(ClearingRecord record, ApprovedAuthorization authorization) {
    return authorization != null
        && !authorization.reversed()
        && authorization.amount().currency().equals(record.amount().currency())
        && !record.amount().isMoreThan(authorization.amount());
  }
}
