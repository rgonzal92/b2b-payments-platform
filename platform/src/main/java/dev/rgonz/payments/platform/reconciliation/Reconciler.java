package dev.rgonz.payments.platform.reconciliation;

/**
 * Who resolves a Break: a person or client with the {@code reconciler} role.
 *
 * @param id their user ID at the identity provider (the token's subject)
 * @param name their user name, for reading the record
 */
record Reconciler(String id, String name) {}
