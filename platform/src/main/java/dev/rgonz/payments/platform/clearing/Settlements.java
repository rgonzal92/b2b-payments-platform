package dev.rgonz.payments.platform.clearing;

import dev.rgonz.payments.platform.ledger.Journal;
import dev.rgonz.payments.platform.shared.Money;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Each Clearing File's Settlement (M3 design, "Settlement"): the Issuer pays the Network the file's
 * net settlement amount, at the Network's figure, and records whether the platform's own total of
 * the file's posted Clearings agrees with it. Each file is settled on its own, whatever its
 * Business Date.
 */
@Repository
class Settlements {

  private final JdbcClient jdbc;
  private final Journal journal;

  Settlements(JdbcClient jdbc, Journal journal) {
    this.jdbc = jdbc;
    this.journal = journal;
  }

  /**
   * Settles the file at the Network's figure, its trailer's net settlement amount: posts it to the
   * journal (debit Network Settlement Payable, credit Issuer Cash, keyed by the file ID) and
   * records the Settlement beside the total of the file's posted Clearings, matched when the two
   * agree. A figure of zero is recorded but posts nothing: a Journal Entry can't move nothing.
   * Settling a file again posts and records nothing more, so a settle step that runs twice (it
   * committed, then stopped before Spring Batch recorded it complete) settles once. Joins the
   * caller's transaction, the settle step's.
   */
  void settle(String fileId, Money networkFigure) {
    // Every Clearing with a Journal Entry counts, whichever Account it debited. Only the trailer's
    // currency: M3 has one, and a sum across currencies would mean nothing.
    var importedTotal =
        jdbc.sql(
                """
                select coalesce(sum(cleared_amount_minor), 0) from clearing.clearing
                 where file_id = :fileId and journal_entry_id is not null
                   and cleared_currency = :currency
                """)
            .param("fileId", fileId)
            .param("currency", networkFigure.currency().getCurrencyCode())
            .query(Long.class)
            .single();
    var journalEntryId =
        networkFigure.minorUnits() == 0 ? null : journal.postSettlement(fileId, networkFigure);
    jdbc.sql(
            """
            insert into clearing.settlement
                   (file_id, amount_minor, imported_total_minor, currency, journal_entry_id)
            values (:fileId, :amount, :importedTotal, :currency, :journalEntryId)
            on conflict (file_id) do nothing
            """)
        .param("fileId", fileId)
        .param("amount", networkFigure.minorUnits())
        .param("importedTotal", importedTotal)
        .param("currency", networkFigure.currency().getCurrencyCode())
        .param("journalEntryId", journalEntryId)
        .update();
  }
}
