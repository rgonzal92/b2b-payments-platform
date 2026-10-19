package dev.rgonz.payments.platform.payables;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Moves a Payment on when authorization-service has its card on file, read from the service's
 * {@code authorizations} topic (M5 design, "The gaps"). payables reads the topic in a consumer
 * group of its own and takes only {@code CardOnFile}: every module that needs the topic reads all
 * of it in its own group, so none waits on another's failures (docs/architecture.md). The offset is
 * committed only after the Payment is saved, so a crash redelivers the event, and the saga ignores
 * what it already applied. {@code KafkaConsumerConfiguration} decides what a failure does.
 */
@Component
class CardOnFileListener {

  static final String GROUP_ID = "platform-payables";
  private static final Logger log = LoggerFactory.getLogger(CardOnFileListener.class);
  private static final String EVENT_TYPE_HEADER = "event-type";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final PaymentSaga saga;

  CardOnFileListener(PaymentSaga saga) {
    this.saga = saga;
  }

  @KafkaListener(topics = "authorizations", groupId = GROUP_ID)
  void onAuthorizationsEvent(ConsumerRecord<String, String> record) {
    var header = record.headers().lastHeader(EVENT_TYPE_HEADER);
    if (header == null) {
      skip("a message with no event-type header", record);
    } else if ("CardOnFile".equals(new String(header.value(), StandardCharsets.UTF_8))) {
      if (record.value() == null) {
        skip("a CardOnFile message with no value", record);
      } else {
        saga.cardOnFile(
            JSON.readValue(record.value(), CardOnFile.class).cardToken(), Instant.now());
      }
    }
    // Another event type is another module's (#92): not one payables uses.
  }

  // Can never be read, so retrying would block the partition for good: logged by partition and
  // offset, never its contents, as the error handler logs malformed JSON.
  private static void skip(String what, ConsumerRecord<String, String> record) {
    log.warn(
        "Skipped {} on the authorizations topic, partition {} offset {}",
        what,
        record.partition(),
        record.offset());
  }

  /**
   * The service's event, as payables reads it: only the Card Token. A message without one can never
   * be read, so it is skipped rather than retried forever.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CardOnFile(String cardToken) {
    CardOnFile {
      Objects.requireNonNull(cardToken, "cardToken");
    }
  }
}
