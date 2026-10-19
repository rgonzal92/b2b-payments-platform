package dev.rgonz.payments.platform.authorization;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feeds the platform's copy of Authorizations from authorization-service's {@code authorizations}
 * topic: its approved and reversed Authorizations. authorization reads the topic in a consumer
 * group of its own and skips {@code CardOnFile}, which is payables' (docs/architecture.md). The
 * offset is committed only after the copy and the card's move commit, so a crash redelivers the
 * event, and the copy ignores what it already applied. {@code KafkaConsumerConfiguration} decides
 * what a failure does.
 */
@Component
class AuthorizationEventsListener {

  static final String GROUP_ID = "platform-authorization";
  private static final Logger log = LoggerFactory.getLogger(AuthorizationEventsListener.class);
  private static final String EVENT_TYPE_HEADER = "event-type";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Authorizations authorizations;

  AuthorizationEventsListener(Authorizations authorizations) {
    this.authorizations = authorizations;
  }

  // The record's timestamp is when the service's relay sent the event: the card's 7 days to clear
  // count from it, not from when the platform caught up.
  @KafkaListener(topics = "authorizations", groupId = GROUP_ID)
  void onAuthorizationsEvent(ConsumerRecord<String, String> record) {
    var header = record.headers().lastHeader(EVENT_TYPE_HEADER);
    if (header == null) {
      skip("a message with no event-type header", record);
      return;
    }
    var eventType = new String(header.value(), StandardCharsets.UTF_8);
    if (!"AuthorizationApproved".equals(eventType) && !"AuthorizationReversed".equals(eventType)) {
      // CardOnFile is payables'.
      return;
    }
    if (record.value() == null) {
      skip("an " + eventType + " message with no value", record);
      return;
    }
    var event = JSON.readValue(record.value(), AuthorizationEvent.class);
    if ("AuthorizationApproved".equals(eventType)) {
      authorizations.approved(event, Instant.ofEpochMilli(record.timestamp()));
    } else {
      authorizations.reversed(event);
    }
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
}
