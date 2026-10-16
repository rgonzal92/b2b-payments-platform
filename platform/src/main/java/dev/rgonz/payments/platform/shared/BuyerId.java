package dev.rgonz.payments.platform.shared;

import java.util.Objects;
import java.util.UUID;

/** Identifies a Buyer across modules; Issuer staff choose it when they create the Buyer. */
public record BuyerId(UUID value) {

  public BuyerId {
    Objects.requireNonNull(value, "value");
  }
}
