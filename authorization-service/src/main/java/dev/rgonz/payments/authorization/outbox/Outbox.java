package dev.rgonz.payments.authorization.outbox;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * The service's outbox: each event for the platform's {@code authorizations} topic is written here
 * in the caller's transaction, so it commits with the change it reports or not at all, and {@link
 * OutboxRelay} sends it to Kafka afterwards, off the hot path. The extra insert rides the same
 * commit, so the hot path gets no extra disk flush (M5 design, "Events and contracts").
 */
@Repository
public class Outbox {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final DatabaseClient db;

  Outbox(DatabaseClient db) {
    this.db = db;
  }

  /**
   * Appends the event, keyed by the card it's about, in the caller's transaction. The event's
   * simple class name becomes its {@code event-type} header and its JSON the message's value.
   */
  public Mono<Void> append(String cardToken, Object event) {
    return Mono.defer(
        () ->
            db.sql(
                    """
                    insert into outbox (card_token, event_type, payload)
                    values (:cardToken, :eventType, :payload)
                    """)
                .bind("cardToken", cardToken)
                .bind("eventType", event.getClass().getSimpleName())
                .bind("payload", JSON.writeValueAsString(event))
                .then());
  }
}
