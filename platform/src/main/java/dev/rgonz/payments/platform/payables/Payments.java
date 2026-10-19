package dev.rgonz.payments.platform.payables;

import dev.rgonz.payments.platform.cards.Cards;
import dev.rgonz.payments.platform.cards.SingleUseCard;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Payments and their saga state (ADR-0014). */
@Repository
class Payments implements InvoicePayments {

  private static final String COLUMNS =
      """
      id, invoice_id, buyer_id, supplier_id, amount_minor, currency, state, failure_reason,
      attempts, next_attempt_at, card_issued_at
      """;

  private final JdbcClient jdbc;
  private final Cards cards;
  private final MeterRegistry meters;

  Payments(JdbcClient jdbc, Cards cards, MeterRegistry meters) {
    this.jdbc = jdbc;
    this.cards = cards;
    this.meters = meters;
    // Each series exists from startup at zero: Prometheus's rate() and increase() need a sample
    // before the first increment, or they miss it.
    for (var state : Payment.State.values()) {
      reached(state);
    }
  }

  /** How many Payments wait for their card to be on file, and since when the oldest has. */
  WaitingForCardOnFile waitingForCardOnFile() {
    return jdbc.sql(
            """
            select count(*), min(card_issued_at)
              from payables.payment
             where state = 'CARD_ISSUED'
            """)
        .query(
            (row, rowNumber) -> {
              var oldest = row.getObject(2, OffsetDateTime.class);
              return new WaitingForCardOnFile(
                  row.getLong(1), oldest == null ? null : oldest.toInstant());
            })
        .single();
  }

  /** {@code oldestSince} is null when none waits. */
  record WaitingForCardOnFile(long count, Instant oldestSince) {}

  @Override
  public List<UnpaidPayment> endedUnpaidOn(LocalDate day) {
    return jdbc.sql(
            """
            select id, invoice_id, buyer_id, amount_minor, currency, state, failure_reason,
                   ended_at
              from payables.payment
             where ended_at >= :from and ended_at < :to
             order by ended_at, id
            """)
        .param("from", day.atStartOfDay().atOffset(ZoneOffset.UTC))
        .param("to", day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC))
        .query(
            (row, rowNumber) ->
                new UnpaidPayment(
                    row.getObject("id", UUID.class),
                    row.getObject("invoice_id", UUID.class),
                    new BuyerId(row.getObject("buyer_id", UUID.class)),
                    new Money(
                        row.getLong("amount_minor"),
                        Currency.getInstance(row.getString("currency"))),
                    row.getString("state"),
                    row.getString("failure_reason"),
                    row.getObject("ended_at", OffsetDateTime.class).toInstant()))
        .list();
  }

  // A card's Payment ID is the card's own, given at issuance.
  @Override
  public Optional<UUID> invoiceForCard(String cardToken) {
    return cards
        .find(cardToken)
        .map(SingleUseCard::paymentId)
        .flatMap(
            paymentId ->
                jdbc.sql("select invoice_id from payables.payment where id = :id")
                    .param("id", paymentId)
                    .query(UUID.class)
                    .optional());
  }

  /**
   * Inserts the Payment unless its Invoice already has one, so a redelivered InvoiceApproved starts
   * no second Payment; true when it was inserted.
   */
  boolean insertUnlessInvoiceHasOne(Payment payment) {
    var inserted =
        jdbc.sql(
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
    if (inserted) {
      countOnCommit(payment.state());
    }
    return inserted;
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
        .param("now", now.atOffset(ZoneOffset.UTC))
        .query(Payments::toPayment)
        .optional();
  }

  /**
   * Saves the Payment's new state, unless the row is no longer as it was when read: another worker
   * has moved the Payment on since, and its state wins. True when it was saved. A Payment that ends
   * {@code FAILED} or {@code EXPIRED} records when, for Reconciliation.
   */
  boolean update(Payment read, Payment next) {
    var saved =
        jdbc.sql(
                    """
                update payables.payment
                   set state = :state,
                       failure_reason = :failureReason,
                       attempts = :attempts,
                       next_attempt_at = :nextAttemptAt,
                       card_issued_at = :cardIssuedAt,
                       ended_at = case when :state in ('FAILED', 'EXPIRED')
                                       then coalesce(ended_at, now()) end
                 where id = :id
                   and state = :readState
                   and attempts = :readAttempts
                """)
                .param("state", next.state().name())
                .param("failureReason", next.failureReason())
                .param("attempts", next.attempts())
                .param("nextAttemptAt", toTimestamp(next.nextAttemptAt()))
                .param("cardIssuedAt", toTimestamp(next.cardIssuedAt()))
                .param("id", read.id())
                .param("readState", read.state().name())
                .param("readAttempts", read.attempts())
                .update()
            == 1;
    if (saved && next.state() != read.state()) {
      countOnCommit(next.state());
    }
    return saved;
  }

  // Counts the Payment reaching the state once its transaction commits, so a rolled-back step
  // counts nothing. The Payments dashboard reads it by state.
  private void countOnCommit(Payment.State state) {
    var counter = reached(state);
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      counter.increment();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            counter.increment();
          }
        });
  }

  private Counter reached(Payment.State state) {
    return Counter.builder("payments.state.changes")
        .description("Payments that reached each saga state")
        .tag("state", state.name())
        .register(meters);
  }

  /**
   * Locks the Payment until the transaction ends, waiting while another transaction (a worker
   * running its step) holds it; empty when there is no such Payment.
   */
  Optional<Payment> lock(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from payables.payment where id = :id for update")
        .param("id", id)
        .query(Payments::toPayment)
        .optional();
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
    var cardIssuedAt = row.getObject("card_issued_at", OffsetDateTime.class);
    return new Payment(
        row.getObject("id", UUID.class),
        row.getObject("invoice_id", UUID.class),
        new BuyerId(row.getObject("buyer_id", UUID.class)),
        row.getObject("supplier_id", UUID.class),
        new Money(row.getLong("amount_minor"), Currency.getInstance(row.getString("currency"))),
        Payment.State.valueOf(row.getString("state")),
        row.getString("failure_reason"),
        row.getInt("attempts"),
        nextAttemptAt == null ? null : nextAttemptAt.toInstant(),
        cardIssuedAt == null ? null : cardIssuedAt.toInstant());
  }
}
