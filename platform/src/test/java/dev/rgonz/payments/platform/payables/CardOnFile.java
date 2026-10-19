package dev.rgonz.payments.platform.payables;

import java.time.Instant;
import org.springframework.context.ApplicationContext;

/**
 * Plays authorization-service's "card on file" for tests outside {@code payables} that run no
 * Kafka, as its listener would on reading it. The saga stays package-private: no other module calls
 * it.
 */
public final class CardOnFile {

  private CardOnFile() {}

  public static void confirm(ApplicationContext context, String cardToken) {
    context.getBean(PaymentSaga.class).cardOnFile(cardToken, Instant.now());
  }
}
