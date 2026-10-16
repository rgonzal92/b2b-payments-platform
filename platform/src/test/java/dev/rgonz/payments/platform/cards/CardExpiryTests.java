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
 * The expiry job on PostgreSQL, run by hand at a chosen time instead of on its schedule. The cards
 * here were issued in 2020, so no other test's card is past its expiry at the times used.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "cards.expiry-job.enabled=false")
class CardExpiryTests {

  private static final Instant ISSUED = Instant.parse("2020-01-01T00:00:00Z");
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

  private SingleUseCard issuedAt(Instant now) {
    return cards.issueSingleUse(
        UUID.randomUUID(), new BuyerId(UUID.randomUUID()), UUID.randomUUID(), AMOUNT, now);
  }

  private SingleUseCard.Status statusOf(SingleUseCard card) {
    return cards.find(card.cardToken()).orElseThrow().status();
  }
}
