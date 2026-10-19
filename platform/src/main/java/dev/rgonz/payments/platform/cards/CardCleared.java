package dev.rgonz.payments.platform.cards;

import org.springframework.modulith.events.Externalized;

/**
 * A Clearing moved a Single-Use Card to {@code CLEARED}: its Charge is final and it accepts no
 * other. Published through Spring Modulith's event publication registry in the same transaction as
 * the status change (ADR-0008), once per card, and externalized to Kafka's {@code cards} topic,
 * keyed by Card Token, so {@code authorization-service} declines Charges on it too (ADR-0016).
 */
@Externalized("cards::#{cardToken}")
public record CardCleared(String cardToken) {}
