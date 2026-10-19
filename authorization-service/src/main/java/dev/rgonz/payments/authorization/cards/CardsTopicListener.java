package dev.rgonz.payments.authorization.cards;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the service's copy of the cards up to date from the platform's {@code cards} topic. The
 * offset is committed only after the copy is written, so a crash redelivers the event, and {@link
 * CardCopies} ignores what it already applied. A message that keeps failing is logged and skipped
 * after Spring Kafka's retries.
 */
@Component
class CardsTopicListener {

  private static final Logger log = LoggerFactory.getLogger(CardsTopicListener.class);

  private final CardEventReader reader = new CardEventReader();
  private final CardCopies copies;

  CardsTopicListener(CardCopies copies) {
    this.copies = copies;
  }

  // The consumer's own thread may block: it isn't on the hot path where the Network waits (M5
  // design), and blocking keeps one partition's events applied in order.
  @KafkaListener(topics = "cards")
  void onCardEvent(ConsumerRecord<String, String> record) {
    var header = record.headers().lastHeader(CardEventReader.EVENT_TYPE_HEADER);
    var eventType = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    reader
        .read(eventType, record.value())
        .ifPresentOrElse(
            event -> copies.apply(event).block(),
            () -> log.warn("Skipped a {} message on the cards topic.", eventType));
  }
}
