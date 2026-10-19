package dev.rgonz.payments.authorization.cards;

import static org.assertj.core.api.Assertions.assertThat;

import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.core.model.V4Interaction;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * What the service needs from each card event on the {@code cards} topic, written as Pact message
 * pacts into the repository's {@code pacts} folder; the platform's provider test checks its real
 * events against them. Each test hands the pact's message to the service's own reader, so a pact
 * the reader can't read fails here.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "platform", providerType = ProviderType.ASYNCH)
class CardEventsPactTests {

  private static final String CONSUMER = "authorization-service";
  private static final String CARD_TOKEN = "q2yJ0p8bX7mN4vL1sT6wZ3";
  // Instant.toString's form, as Jackson writes an Instant: seconds, then any fraction, then Z.
  private static final String INSTANT = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$";

  private final CardEventReader reader = new CardEventReader();

  @Pact(consumer = CONSUMER)
  V4Pact cardIssued(PactBuilder builder) {
    return message(
        builder,
        "a CardIssued for a newly issued card",
        "CardIssued",
        new PactDslJsonBody()
            .stringType("cardToken", CARD_TOKEN)
            .stringType("panHash", "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            .stringType("securityCodeHash", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            .stringMatcher("expiresAt", INSTANT, "2026-11-17T09:30:00.123456Z")
            .integerType("amountMinorUnits", 1_240_000L)
            .stringMatcher("currency", "^[A-Z]{3}$", "USD")
            .stringType("acceptorId", "ACCEPTOR-1"));
  }

  // The platform sends no Acceptor ID when no Invoice gave the Supplier one; the service stores
  // the card anyway, and will decline its Charges.
  @Pact(consumer = CONSUMER)
  V4Pact cardIssuedWithoutAcceptorId(PactBuilder builder) {
    return message(
        builder,
        "a CardIssued for a card whose Supplier has no Acceptor ID",
        "CardIssued",
        new PactDslJsonBody()
            .stringType("cardToken", CARD_TOKEN)
            .stringType("panHash", "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            .stringType("securityCodeHash", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            .stringMatcher("expiresAt", INSTANT, "2026-11-17T09:30:00.123456Z")
            .integerType("amountMinorUnits", 1_240_000L)
            .stringMatcher("currency", "^[A-Z]{3}$", "USD")
            .nullValue("acceptorId"));
  }

  @Pact(consumer = CONSUMER)
  V4Pact cardCancelled(PactBuilder builder) {
    return message(
        builder,
        "a CardCancelled for a cancelled card",
        "CardCancelled",
        new PactDslJsonBody().stringType("cardToken", CARD_TOKEN));
  }

  @Pact(consumer = CONSUMER)
  V4Pact cardExpired(PactBuilder builder) {
    return message(
        builder,
        "a CardExpired for an expired card",
        "CardExpired",
        new PactDslJsonBody().stringType("cardToken", CARD_TOKEN));
  }

  @Test
  @PactTestFor(pactMethod = "cardIssued")
  void readsCardIssued(V4Interaction.AsynchronousMessage message) {
    assertThat(read(message))
        .isEqualTo(
            new CardEvent.CardIssued(
                CARD_TOKEN,
                "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                Instant.parse("2026-11-17T09:30:00.123456Z"),
                1_240_000L,
                Currency.getInstance("USD"),
                "ACCEPTOR-1"));
  }

  @Test
  @PactTestFor(pactMethod = "cardIssuedWithoutAcceptorId")
  void readsCardIssuedWithoutAcceptorId(V4Interaction.AsynchronousMessage message) {
    assertThat(read(message))
        .isEqualTo(
            new CardEvent.CardIssued(
                CARD_TOKEN,
                "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                Instant.parse("2026-11-17T09:30:00.123456Z"),
                1_240_000L,
                Currency.getInstance("USD"),
                null));
  }

  @Test
  @PactTestFor(pactMethod = "cardCancelled")
  void readsCardCancelled(V4Interaction.AsynchronousMessage message) {
    assertThat(read(message)).isEqualTo(new CardEvent.CardCancelled(CARD_TOKEN));
  }

  @Test
  @PactTestFor(pactMethod = "cardExpired")
  void readsCardExpired(V4Interaction.AsynchronousMessage message) {
    assertThat(read(message)).isEqualTo(new CardEvent.CardExpired(CARD_TOKEN));
  }

  // Each message names its event type in the event-type header, which Pact calls metadata.
  private static V4Pact message(
      PactBuilder builder, String description, String eventType, PactDslJsonBody body) {
    return builder
        .expectsToReceiveMessageInteraction(
            description,
            interaction ->
                interaction.withContents(
                    contents ->
                        contents
                            .withMetadata(Map.of(CardEventReader.EVENT_TYPE_HEADER, eventType))
                            .withContent(body)))
        .toPact();
  }

  private CardEvent read(V4Interaction.AsynchronousMessage message) {
    var eventType =
        String.valueOf(message.getContents().getMetadata().get(CardEventReader.EVENT_TYPE_HEADER));
    return reader.read(eventType, message.contentsAsString()).orElseThrow();
  }
}
