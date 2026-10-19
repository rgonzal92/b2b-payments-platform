package dev.rgonz.payments.platform.payables;

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
 * What payables needs from authorization-service's {@code CardOnFile} on the {@code authorizations}
 * topic, written as a Pact message pact into the repository's {@code pacts} folder; the service's
 * provider test checks its real event against it. The test hands the pact's message to payables'
 * own listener, so a pact the listener can't read fails here.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "authorization-service", providerType = ProviderType.ASYNCH)
class CardOnFilePactTests {

  private final PaymentSaga saga = mock(PaymentSaga.class);
  private final CardOnFileListener listener = new CardOnFileListener(saga);

  @Pact(consumer = "platform")
  V4Pact cardOnFile(PactBuilder builder) {
    return builder
        .expectsToReceiveMessageInteraction(
            "a CardOnFile for a card the service holds",
            interaction ->
                interaction.withContents(
                    contents ->
                        contents
                            .withMetadata(Map.of("event-type", "CardOnFile"))
                            .withContent(
                                new PactDslJsonBody().stringType("cardToken", "card-token-1"))))
        .toPact();
  }

  @Test
  @PactTestFor(pactMethod = "cardOnFile")
  void readsCardOnFile(V4Interaction.AsynchronousMessage message) {
    var record =
        new ConsumerRecord<>("authorizations", 0, 0L, "card-token-1", message.contentsAsString());
    message
        .getContents()
        .getMetadata()
        .forEach((key, value) -> record.headers().add(key, String.valueOf(value).getBytes(UTF_8)));

    listener.onAuthorizationsEvent(record);

    verify(saga).cardOnFile(eq("card-token-1"), any(Instant.class));
  }
}
