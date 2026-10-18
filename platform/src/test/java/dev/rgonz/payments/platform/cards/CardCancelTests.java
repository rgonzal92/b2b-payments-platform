package dev.rgonz.payments.platform.cards;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Cancelling a Single-Use Card on PostgreSQL. The expired card was issued in 2020 and the expiry
 * job run by hand at its expiry, as in {@code CardExpiryTests}, so no other test's card expires.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "cards.expiry-job.enabled=false")
class CardCancelTests {

  private static final Money AMOUNT = new Money(1_240_000, Currency.getInstance("USD"));

  @Autowired Cards cards;
  @Autowired CardExpiry expiry;

  @Test
  void onlyAnActiveCardIsCancelled() {
    var active = issuedAt(Instant.now());
    var authorized = issuedAt(Instant.now());
    cards.authorize(authorized.cardToken(), Instant.now());
    var expired = issuedAt(Instant.parse("2020-01-01T00:00:00Z"));
    expiry.expireDue(expired.expiresAt());

    cards.cancel(active.cardToken());
    cards.cancel(active.cardToken());
    cards.cancel(authorized.cardToken());
    cards.cancel(expired.cardToken());

    assertThat(statusOf(active)).isEqualTo(SingleUseCard.Status.CANCELLED);
    assertThat(statusOf(authorized)).isEqualTo(SingleUseCard.Status.AUTHORIZED);
    assertThat(statusOf(expired)).isEqualTo(SingleUseCard.Status.EXPIRED);
  }

  private SingleUseCard issuedAt(Instant now) {
    return cards.issueSingleUse(
        UUID.randomUUID(), new BuyerId(UUID.randomUUID()), UUID.randomUUID(), null, AMOUNT, now);
  }

  private SingleUseCard.Status statusOf(SingleUseCard card) {
    return cards.find(card.cardToken()).orElseThrow().status();
  }
}
