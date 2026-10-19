package dev.rgonz.payments.authorization.outbox;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Sends the outbox's unsent rows to the {@code authorizations} topic in id order, one at a time,
 * and marks each sent once Kafka has acknowledged it. A send that fails ends the pass: the rows
 * marked so far commit, and the failed row and those after it wait for the next pass, so a card's
 * events never reach the topic out of order. A crash before the pass commits leaves its rows
 * unsent, and the next pass sends them again: a repeat that the platform's consumer, keyed on each
 * event's natural ID, ignores.
 *
 * <p>One relay runs at a time, across every instance of the service: each pass is one transaction
 * that first takes a PostgreSQL advisory lock, and a pass that can't take it does nothing. {@code
 * FOR UPDATE SKIP LOCKED} was the alternative, but it would let two instances send disjoint batches
 * at once, and so one card's events out of order.
 *
 * <p>Each row is sent in the trace it was written in ({@link Outbox}): the relay continues that
 * trace for the send, and Spring Kafka's observation puts it in the message's {@code traceparent}
 * header, so the platform's consumer span joins the Network's call's trace (#96).
 */
@Component
class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  static final String TOPIC = "authorizations";
  // Bounds one pass's transaction; whatever is left goes in the next pass, moments later.
  private static final int BATCH_SIZE = 500;

  private final DatabaseClient db;
  private final KafkaTemplate<String, String> kafka;
  private final ObservationRegistry observations;

  OutboxRelay(
      DatabaseClient db, KafkaTemplate<String, String> kafka, ObservationRegistry observations) {
    this.db = db;
    this.kafka = kafka;
    this.observations = observations;
  }

  /** Runs one pass and gives how many rows it sent: none while another pass holds the lock. */
  @Transactional
  Mono<Long> relay() {
    return db.sql("select pg_try_advisory_xact_lock(hashtext('outbox-relay')) as locked")
        .map(row -> row.get("locked", Boolean.class))
        .one()
        .flatMap(locked -> Boolean.TRUE.equals(locked) ? sendUnsent() : Mono.just(0L));
  }

  private Mono<Long> sendUnsent() {
    return db.sql(
            """
            select id, card_token, event_type, payload, created_at, trace_parent
              from outbox
             where sent_at is null
             order by id
             limit :batchSize
            """)
        .bind("batchSize", BATCH_SIZE)
        .map(
            row ->
                new Row(
                    row.get("id", Long.class),
                    row.get("card_token", String.class),
                    row.get("event_type", String.class),
                    row.get("payload", String.class),
                    row.get("created_at", OffsetDateTime.class).toInstant(),
                    row.get("trace_parent", String.class)))
        .all()
        // Read every row before the first send, so the select's results are done with before the
        // same connection runs the updates.
        .collectList()
        .flatMapMany(Flux::fromIterable)
        // The first send that fails ends the pass, and takeWhile cancels the rest before they're
        // sent. A database error isn't caught: it fails the pass, which then marks nothing.
        .concatMap(row -> send(row).flatMap(sent -> sent ? markSent(row.id()) : Mono.just(false)))
        .takeWhile(sent -> sent)
        .count();
  }

  // The record's timestamp is the row's created_at, the time of the transaction that wrote the
  // event, so a consumer reading when it happened isn't misled by a late send after Kafka was down.
  // The template may block, up to max.block.ms while Kafka is unreachable, so the send starts on a
  // thread that may wait, never on R2DBC's event loop, which the hot path shares.
  private Mono<Boolean> send(Row row) {
    var record =
        new ProducerRecord<>(
            TOPIC, null, row.createdAt().toEpochMilli(), row.cardToken(), row.payload());
    record
        .headers()
        .add(Outbox.EVENT_TYPE_HEADER, row.eventType().getBytes(StandardCharsets.UTF_8));
    return Mono.fromFuture(() -> sendInItsTrace(record, row.traceParent()))
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(
            error ->
                log.warn(
                    "Outbox row {} ({}) wasn't sent; the next pass starts from it",
                    row.id(),
                    row.eventType(),
                    error))
        .thenReturn(true)
        .onErrorReturn(false);
  }

  // The row's trace, read from its traceparent as a consumer reads a message's, is the parent of
  // the template's own send observation, which writes the header.
  private CompletableFuture<?> sendInItsTrace(
      ProducerRecord<String, String> record, String traceParent) {
    if (traceParent == null) {
      return kafka.send(record);
    }
    var context = new ReceiverContext<Map<String, String>>(Map::get);
    context.setCarrier(Map.of(Outbox.TRACE_PARENT, traceParent));
    return Observation.createNotStarted("outbox.relay", () -> context, observations)
        .contextualName("outbox relay")
        .observe(() -> kafka.send(record));
  }

  private Mono<Boolean> markSent(long id) {
    return db.sql("update outbox set sent_at = clock_timestamp() where id = :id")
        .bind("id", id)
        .then()
        .thenReturn(true);
  }

  private record Row(
      long id,
      String cardToken,
      String eventType,
      String payload,
      Instant createdAt,
      String traceParent) {}
}
