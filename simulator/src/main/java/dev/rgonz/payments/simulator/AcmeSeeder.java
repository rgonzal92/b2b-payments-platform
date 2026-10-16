package dev.rgonz.payments.simulator;

import dev.rgonz.payments.contracts.client.BuyersApi;
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

/** Creates the Buyer Acme on the platform at startup, acting as Issuer staff. */
@Component
@ConditionalOnBooleanProperty("simulator.seed.enabled")
class AcmeSeeder {

  private static final Logger log = LoggerFactory.getLogger(AcmeSeeder.class);

  private final BuyersApi buyers;
  private final UUID acmeId;

  // The platform may still be starting: keep trying for about half a minute.
  private final RetryTemplate retry =
      new RetryTemplate(RetryPolicy.builder().maxRetries(10).delay(Duration.ofSeconds(3)).build());

  AcmeSeeder(BuyersApi buyers, @Value("${simulator.acme.buyer-id}") UUID acmeId) {
    this.buyers = buyers;
    this.acmeId = acmeId;
  }

  @EventListener(ApplicationReadyEvent.class)
  void createAcme() {
    var request =
        new BuyerRequest()
            .name("Acme")
            .approvalThreshold(new Money().minorUnits(1_000_000L).currency(Money.CurrencyEnum.USD));
    try {
      retry.invoke(() -> buyers.putBuyer(acmeId, request));
      log.info("Acme is ready as Buyer {}", acmeId);
    } catch (RuntimeException e) {
      log.warn("Couldn't create Acme on the platform; is it running?", e);
    }
  }
}
