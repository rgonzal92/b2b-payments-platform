package dev.rgonz.payments.platform.cards;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.modulith.test.PublishedEventsExtension;

/**
 * The expiry job on PostgreSQL, run by hand at a chosen time instead of on its schedule. The cards
 * here were issued in 2020, so no other test's card is past its expiry at the times used.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "cards.expiry-job.enabled=false")
@ExtendWith(PublishedEventsExtension.class)
class CardExpiryTests {

  private static final Instant ISSUED = Instant.parse("2020-01-01T00:00:00Z");
  // A day after issuance, so the 7 days end long before the card's own 30-day expiry.
  private static final Instant AUTHORIZED_AT = ISSUED.plus(Duration.ofDays(1));
  private static final Money AMOUNT = new Money(1_240_000, Currency.getInstance("USD"));

  @Autowired Cards cards;
  @Autowired CardExpiry expiry;

  @Test
  void onlyAnActiveCardWhoseExpiryHasComeExpires() {
    var due = issuedAt(ISSUED);
    var notYetDue = issuedAt(ISSUED.plusSeconds(1));
    var cancelled = issuedAt(ISSUED);
    cards.cancel(cancelled.cardToken());

    expiry.expireDue(due.expiresAt());

    assertThat(statusOf(due)).isEqualTo(SingleUseCard.Status.EXPIRED);
    assertThat(statusOf(notYetDue)).isEqualTo(SingleUseCard.Status.ACTIVE);
    assertThat(statusOf(cancelled)).isEqualTo(SingleUseCard.Status.CANCELLED);
  }

  @Test
  void anAuthorizedCardExpiresSevenDaysAfterItsAuthorizationAndPublishesCardExpired(
      PublishedEvents events) {
    var card = issuedAt(ISSUED);
    cards.authorize(card.cardToken(), AUTHORIZED_AT);

    expiry.expireDue(AUTHORIZED_AT.plus(Duration.ofDays(7)));

    assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.EXPIRED);
    assertThat(
            events
                .ofType(CardExpired.class)
                .matching(expired -> expired.cardToken().equals(card.cardToken())))
        .containsExactly(
            new CardExpired(card.cardToken(), card.paymentId(), card.buyerId(), card.amount()));
  }

  @Test
  void aCardAuthorizedLessThanSevenDaysAgoStaysAuthorized() {
    var card = issuedAt(ISSUED);
    cards.authorize(card.cardToken(), AUTHORIZED_AT);

    expiry.expireDue(AUTHORIZED_AT.plus(Duration.ofDays(7)).minusSeconds(1));

    assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.AUTHORIZED);
  }

  @Test
  void aCardReversedBackToActiveExpiresOnlyAtItsOwnExpiry() {
    var card = issuedAt(ISSUED);
    cards.authorize(card.cardToken(), AUTHORIZED_AT);
    cards.reverse(card.cardToken());

    expiry.expireDue(AUTHORIZED_AT.plus(Duration.ofDays(7)));
    var afterSevenDays = statusOf(card);
    expiry.expireDue(card.expiresAt());

    assertThat(afterSevenDays).isEqualTo(SingleUseCard.Status.ACTIVE);
    assertThat(statusOf(card)).isEqualTo(SingleUseCard.Status.EXPIRED);
  }

  private SingleUseCard issuedAt(Instant now) {
    return cards.issueSingleUse(
        UUID.randomUUID(), new BuyerId(UUID.randomUUID()), UUID.randomUUID(), AMOUNT, now);
  }

  private SingleUseCard.Status statusOf(SingleUseCard card) {
    return cards.find(card.cardToken()).orElseThrow().status();
  }
}
