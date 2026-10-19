package dev.rgonz.payments.platform.authorization;

/**
 * Whether the platform's Authorization Copy has caught up with authorization-service, for Clearing
 * to wait on before it matches a Clearing File against the copy.
 */
public interface AuthorizationCopy {

  /**
   * True when the copy's consumer group has committed every event on the {@code authorizations}
   * topic; false while it lags, or when Kafka doesn't answer within seconds. It can't see events
   * still in the service's outbox (docs/runbooks/kafka-down.md).
   */
  boolean caughtUp();
}
