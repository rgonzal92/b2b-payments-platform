package dev.rgonz.payments.authorization.outbox;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * The service's outbox: each event for the platform's {@code authorizations} topic is written here
 * in the caller's transaction, so it commits with the change it reports or not at all, and {@link
 * OutboxRelay} sends it to Kafka afterwards, off the hot path. The extra insert rides the same
 * commit, so the hot path gets no extra disk flush (M5 design, "Events and contracts").
 *
 * <p>Each row keeps the W3C {@code traceparent} of the trace it was written in, such as the
 * Network's call, and the relay sends the event in that trace (#96). Reactor's automatic context
 * propagation makes the caller's trace current here, whichever thread the insert runs on.
 */
@Repository
public class Outbox {

  /** The header naming each message's event type, on both topics, as the platform names it. */
  public static final String EVENT_TYPE_HEADER = "event-type";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The W3C trace context header, as Spring Kafka's observation reads it on the platform. */
  static final String TRACE_PARENT = "traceparent";

  private final DatabaseClient db;
  private final Tracer tracer;
  private final Propagator propagator;

  Outbox(DatabaseClient db, Tracer tracer, Propagator propagator) {
    this.db = db;
    this.tracer = tracer;
    this.propagator = propagator;
  }

  /**
   * Appends the event, keyed by the card it's about, in the caller's transaction. The event's
   * simple class name becomes its {@code event-type} header and its JSON the message's value.
   */
  public Mono<Void> append(String cardToken, Object event) {
    return Mono.defer(
        () -> {
          var insert =
              db.sql(
                      """
                      insert into outbox (card_token, event_type, payload, trace_parent)
                      values (:cardToken, :eventType, :payload, :traceParent)
                      """)
                  .bind("cardToken", cardToken)
                  .bind("eventType", event.getClass().getSimpleName())
                  .bind("payload", JSON.writeValueAsString(event));
          var traceParent = currentTraceParent();
          return (traceParent == null
                  ? insert.bindNull("traceParent", String.class)
                  : insert.bind("traceParent", traceParent))
              .then();
        });
  }

  // Null when no trace is current.
  private String currentTraceParent() {
    var span = tracer.currentSpan();
    if (span == null) {
      return null;
    }
    var headers = new HashMap<String, String>();
    propagator.inject(span.context(), headers, HashMap::put);
    return headers.get(TRACE_PARENT);
  }
}
