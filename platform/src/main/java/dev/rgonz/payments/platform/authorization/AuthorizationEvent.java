package dev.rgonz.payments.platform.authorization;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Objects;

/**
 * authorization-service's {@code AuthorizationApproved} or {@code AuthorizationReversed}, as the
 * platform reads it: both carry the approved Authorization's fields and share its request ID. The
 * platform keeps its own record, with no class shared with the service. One missing a field can
 * never be stored, so it is skipped rather than retried forever.
 *
 * @param requestId the Network's retrieval reference number (DE 37)
 * @param cardToken its card, never the PAN (ADR-0004)
 * @param amountMinorUnits the Charge's amount (DE 4), in minor units of {@code currency}
 * @param currency the Charge's ISO 4217 currency code (DE 49)
 * @param authorizationCode the approved Authorization's code (DE 38)
 * @param acceptorId the Card Acceptor ID (DE 42)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthorizationEvent(
    String requestId,
    String cardToken,
    Long amountMinorUnits,
    String currency,
    String authorizationCode,
    String acceptorId) {

  public AuthorizationEvent {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(cardToken, "cardToken");
    Objects.requireNonNull(amountMinorUnits, "amountMinorUnits");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(authorizationCode, "authorizationCode");
    Objects.requireNonNull(acceptorId, "acceptorId");
  }
}
