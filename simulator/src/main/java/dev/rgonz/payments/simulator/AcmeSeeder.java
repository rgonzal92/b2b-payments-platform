package dev.rgonz.payments.simulator;

import dev.rgonz.payments.contracts.client.BuyersApi;
import dev.rgonz.payments.contracts.client.CreditApi;
import dev.rgonz.payments.contracts.client.model.BuyerRequest;
import dev.rgonz.payments.contracts.client.model.Money;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;

/** Creates the Buyer Acme and gives it a Credit Line at startup, acting as Issuer staff. */
@Component
@ConditionalOnBooleanProperty("simulator.seed.enabled")
class AcmeSeeder {

  private static final Logger log = LoggerFactory.getLogger(AcmeSeeder.class);

  private final BuyersApi buyers;
  private final CreditApi credit;
  private final UUID acmeId;

  // The platform may still be starting: keep trying for about two minutes.
  private final RetryTemplate retry =
      new RetryTemplate(RetryPolicy.builder().maxRetries(40).delay(Duration.ofSeconds(3)).build());

  AcmeSeeder(BuyersApi buyers, CreditApi credit, @Value("${simulator.acme.buyer-id}") UUID acmeId) {
    this.buyers = buyers;
    this.credit = credit;
    this.acmeId = acmeId;
  }

  @EventListener(ApplicationReadyEvent.class)
  void seedAcme() {
    var request =
        new BuyerRequest()
            .name("Acme")
            .approvalThreshold(new Money().minorUnits(1_000_000L).currency(Money.CurrencyEnum.USD));
    // $100,000.
    var creditLine = new Money().minorUnits(10_000_000L).currency(Money.CurrencyEnum.USD);
    // Without Acme and its credit every scenario fails, so a platform that never answers stops the
    // simulator's startup. Both requests are safe to repeat.
    retry.invoke(() -> buyers.putBuyer(acmeId, request));
    retry.invoke(() -> credit.putCreditLine(acmeId, creditLine));
    log.info("Acme is ready as Buyer {} with a Credit Line", acmeId);
  }
}
