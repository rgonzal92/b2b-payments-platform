package dev.rgonz.payments.platform.payables;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Payments and their saga state (ADR-0014). */
@Repository
class Payments {

  private static final String COLUMNS =
      """
      id, invoice_id, buyer_id, supplier_id, amount_minor, currency, state, failure_reason,
      attempts, next_attempt_at
      """;

  private final JdbcClient jdbc;

  Payments(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts the Payment unless its Invoice already has one, so a redelivered InvoiceApproved starts
   * no second Payment; true when it was inserted.
   */
  boolean insertUnlessInvoiceHasOne(Payment payment) {
    return jdbc.sql(
                """
                insert into payables.payment
                       (id, invoice_id, buyer_id, supplier_id, amount_minor, currency, state, attempts,
                        next_attempt_at)
                values (:id, :invoiceId, :buyerId, :supplierId, :amount, :currency, :state, :attempts,
                        :nextAttemptAt)
                on conflict (invoice_id) do nothing
                """)
            .param("id", payment.id())
            .param("invoiceId", payment.invoiceId())
            .param("buyerId", payment.buyerId().value())
            .param("supplierId", payment.supplierId())
            .param("amount", payment.amount().minorUnits())
            .param("currency", payment.amount().currency().getCurrencyCode())
            .param("state", payment.state().name())
            .param("attempts", payment.attempts())
            .param("nextAttemptAt", toTimestamp(payment.nextAttemptAt()))
            .update()
        == 1;
  }

  /**
   * Locks the Payment whose next step has been due longest, skipping Payments other transactions
   * have locked, until the transaction ends; empty when none is due.
   */
  Optional<Payment> claimNextDue(Instant now) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + """
                  from payables.payment
                 where next_attempt_at <= :now
                 order by next_attempt_at
                 limit 1
                   for update skip locked
                """)
        .param("now", toTimestamp(now))
        .query(Payments::toPayment)
        .optional();
  }

  /**
   * Saves the Payment's new state, unless the row is no longer as it was when read: another worker
   * has moved the Payment on since, and its state wins. True when it was saved.
   */
  boolean update(Payment read, Payment next) {
    return jdbc.sql(
                """
                update payables.payment
                   set state = :state,
                       failure_reason = :failureReason,
                       attempts = :attempts,
                       next_attempt_at = :nextAttemptAt
                 where id = :id
                   and state = :readState
                   and attempts = :readAttempts
                """)
            .param("state", next.state().name())
            .param("failureReason", next.failureReason())
            .param("attempts", next.attempts())
            .param("nextAttemptAt", toTimestamp(next.nextAttemptAt()))
            .param("id", read.id())
            .param("readState", read.state().name())
            .param("readAttempts", read.attempts())
            .update()
        == 1;
  }

  Optional<Payment> findForInvoice(UUID invoiceId) {
    return jdbc.sql("select " + COLUMNS + " from payables.payment where invoice_id = :invoiceId")
        .param("invoiceId", invoiceId)
        .query(Payments::toPayment)
        .optional();
  }

  private static OffsetDateTime toTimestamp(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  private static Payment toPayment(ResultSet row, int rowNumber) throws SQLException {
    var nextAttemptAt = row.getObject("next_attempt_at", OffsetDateTime.class);
    return new Payment(
        row.getObject("id", UUID.class),
        row.getObject("invoice_id", UUID.class),
        new BuyerId(row.getObject("buyer_id", UUID.class)),
        row.getObject("supplier_id", UUID.class),
        new Money(row.getLong("amount_minor"), Currency.getInstance(row.getString("currency"))),
        Payment.State.valueOf(row.getString("state")),
        row.getString("failure_reason"),
        row.getInt("attempts"),
        nextAttemptAt == null ? null : nextAttemptAt.toInstant());
  }
}
