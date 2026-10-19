package dev.rgonz.payments.authorization.cards;

/**
 * The service now holds the card with its issue details and can authorize it: published on the
 * {@code authorizations} topic, through the outbox, when a {@code CardIssued} first stores the
 * card. The platform's saga waits for it before sending the Supplier the card (M5 design, "The
 * gaps"). A card that ended before its {@code CardIssued} arrived is never on file, and a repeated
 * {@code CardIssued} publishes nothing more. The platform keys its write on the Card Token, so a
 * repeat changes nothing.
 */
record CardOnFile(String cardToken) {}
