package dev.rgonz.payments.platform.vault;

/**
 * A card's PAN and security code, keyed-hashed with HMAC-SHA-256 under {@code vault.hash-key} and
 * base64-encoded. Safe to publish: without the key no one can test guesses against a hash, and the
 * PAN itself never appears.
 */
public record CardHashes(String panHash, String securityCodeHash) {}
