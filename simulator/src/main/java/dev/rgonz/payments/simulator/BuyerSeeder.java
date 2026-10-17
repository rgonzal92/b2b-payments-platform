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

/**
 * Creates the Buyers Acme (the demo's) and Initech (the load test's) and gives each a Credit Line
 * at startup, acting as Issuer staff.
 */
@Component
@ConditionalOnBooleanProperty("simulator.seed.enabled")
class BuyerSeeder {

  private static final Logger log = LoggerFactory.getLogger(BuyerSeeder.class);

  private final BuyersApi buyers;
  private final CreditApi credit;
  private final UUID acmeId;
  private final UUID initechId;

  // The platform may still be starting: keep trying for about two minutes.
  private final RetryTemplate retry =
      new RetryTemplate(RetryPolicy.builder().maxRetries(40).delay(Duration.ofSeconds(3)).build());

  BuyerSeeder(
      BuyersApi buyers,
      CreditApi credit,
      @Value("${simulator.acme.buyer-id}") UUID acmeId,
      @Value("${simulator.initech.buyer-id}") UUID initechId) {
    this.buyers = buyers;
    this.credit = credit;
    this.acmeId = acmeId;
    this.initechId = initechId;
  }

  @EventListener(ApplicationReadyEvent.class)
  void seedBuyers() {
    // Approval Threshold $10,000, Credit Line $100,000.
    seed(acmeId, "Acme", usd(1_000_000L), usd(10_000_000L));
    // Approval Threshold $100, so one Approver approves each $10 load-test Invoice; Credit Line
    // $1,000,000, enough for 100,000 of them.
    seed(initechId, "Initech", usd(10_000L), usd(100_000_000L));
  }

  // Without its Buyers and their credit every scenario fails, so a platform that never answers
  // stops the simulator's startup. Both requests are safe to repeat.
  private void seed(UUID buyerId, String name, Money approvalThreshold, Money creditLine) {
    var request = new BuyerRequest().name(name).approvalThreshold(approvalThreshold);
    retry.invoke(() -> buyers.putBuyer(buyerId, request));
    retry.invoke(() -> credit.putCreditLine(buyerId, creditLine));
    log.info("{} is ready as Buyer {} with a Credit Line", name, buyerId);
  }

  private static Money usd(long minorUnits) {
    return new Money().minorUnits(minorUnits).currency(Money.CurrencyEnum.USD);
  }
}
