package dev.rgonz.payments.authorization.cards;

import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a message from the {@code cards} topic into a {@link CardEvent}: the {@code event-type}
 * header names the event, and the value is its JSON. The Pact consumer tests read every pact
 * message through here.
 */
class CardEventReader {

  static final String EVENT_TYPE_HEADER = "event-type";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The event, or empty for an event type the service doesn't use. */
  Optional<CardEvent> read(String eventType, String json) {
    var type =
        switch (eventType) {
          case "CardIssued" -> CardEvent.CardIssued.class;
          case "CardCancelled" -> CardEvent.CardCancelled.class;
          case "CardExpired" -> CardEvent.CardExpired.class;
          case null, default -> null;
        };
    return Optional.ofNullable(type).map(eventClass -> JSON.readValue(json, eventClass));
  }
}
