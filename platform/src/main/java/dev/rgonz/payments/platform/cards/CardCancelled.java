package dev.rgonz.payments.platform.cards;

import org.springframework.modulith.events.Externalized;

/**
 * A Single-Use Card was cancelled and accepts no Charge again. Published through Spring Modulith's
 * event publication registry in the same transaction as the status change (ADR-0008) and
 * externalized to Kafka's {@code cards} topic, keyed by Card Token, so {@code
 * authorization-service} refuses charges on it too (ADR-0016).
 */
@Externalized("cards::#{cardToken}")
public record CardCancelled(String cardToken) {}
