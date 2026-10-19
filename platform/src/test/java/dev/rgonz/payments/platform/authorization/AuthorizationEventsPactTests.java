package dev.rgonz.payments.platform.authorization;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.core.model.V4Interaction;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * What the platform's copy of Authorizations needs from authorization-service's approved and
 * reversed Authorizations on the {@code authorizations} topic, written as Pact message pacts into
 * the repository's {@code pacts} folder; the service's provider test checks its real events against
 * them. Each test hands the pact's message to the platform's own listener, so a pact the listener
 * can't read fails here.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "authorization-service", providerType = ProviderType.ASYNCH)
class AuthorizationEventsPactTests {

  private static final String CONSUMER = "platform";
  private static final AuthorizationEvent EVENT =
      new AuthorizationEvent(
          "Q00000000001", "card-token-1", 1_240_000L, "USD", "ABC123", "ACCEPTOR-1");

  private final Authorizations authorizations = mock(Authorizations.class);
  private final AuthorizationEventsListener listener =
      new AuthorizationEventsListener(authorizations);

  @Pact(consumer = CONSUMER)
  V4Pact authorizationApproved(PactBuilder builder) {
    return message(
        builder, "an AuthorizationApproved for an approved Charge", "AuthorizationApproved");
  }

  @Pact(consumer = CONSUMER)
  V4Pact authorizationReversed(PactBuilder builder) {
    return message(
        builder,
        "an AuthorizationReversed for a reversed approved Authorization",
        "AuthorizationReversed");
  }

  @Test
  @PactTestFor(pactMethod = "authorizationApproved")
  void readsAuthorizationApproved(V4Interaction.AsynchronousMessage message) {
    listener.onAuthorizationsEvent(record(message));

    verify(authorizations).approved(eq(EVENT), any(Instant.class));
  }

  @Test
  @PactTestFor(pactMethod = "authorizationReversed")
  void readsAuthorizationReversed(V4Interaction.AsynchronousMessage message) {
    listener.onAuthorizationsEvent(record(message));

    verify(authorizations).reversed(EVENT);
  }

  // Both events carry the approved Authorization's fields. Each message names its event type in
  // the event-type header, which Pact calls metadata.
  private static V4Pact message(PactBuilder builder, String description, String eventType) {
    return builder
        .expectsToReceiveMessageInteraction(
            description,
            interaction ->
                interaction.withContents(
                    contents ->
                        contents
                            .withMetadata(Map.of("event-type", eventType))
                            .withContent(
                                new PactDslJsonBody()
                                    .stringType("requestId", "Q00000000001")
                                    .stringType("cardToken", "card-token-1")
                                    .integerType("amountMinorUnits", 1_240_000L)
                                    .stringMatcher("currency", "^[A-Z]{3}$", "USD")
                                    .stringMatcher("authorizationCode", "^[A-Z0-9]{6}$", "ABC123")
                                    .stringType("acceptorId", "ACCEPTOR-1"))))
        .toPact();
  }

  // The message as the listener receives it from Kafka, its metadata as headers.
  private static ConsumerRecord<String, String> record(V4Interaction.AsynchronousMessage message) {
    var record =
        new ConsumerRecord<>("authorizations", 0, 0L, "card-token-1", message.contentsAsString());
    message
        .getContents()
        .getMetadata()
        .forEach((key, value) -> record.headers().add(key, String.valueOf(value).getBytes(UTF_8)));
    return record;
  }
}
