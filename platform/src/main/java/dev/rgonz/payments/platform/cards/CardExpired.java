package dev.rgonz.payments.platform.cards;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * A Single-Use Card reached its expiry before anyone charged it, or its approved Charge didn't
 * clear within 7 days, and is now {@code EXPIRED}. Published through Spring Modulith's event
 * publication registry in the same transaction as the status change (ADR-0008), so it is delivered
 * at least once; {@code payables} releases the card's Hold and ends its Payment, and it is
 * externalized to Kafka's {@code cards} topic for {@code authorization-service} (ADR-0016).
 */
@Externalized("cards::#{cardToken}")
public record CardExpired(String cardToken, UUID paymentId, BuyerId buyerId, Money amount) {}
