package dev.rgonz.payments.authorization.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/**
 * The relay's schedule makes no observation of its own, so no trace every 100 ms; every other
 * observation, another schedule's included, is kept.
 */
class OutboxConfigurationTests {

  private static final String SCHEDULED_TASK = "tasks.scheduled.execution";

  private final ObservationPredicate predicate =
      new OutboxConfiguration().noRelayPassObservations();

  @Test
  void aRelayPassIsNotObserved() throws NoSuchMethodException {
    var relayPass =
        new ScheduledTaskObservationContext(
            new OutboxRelaySchedule(null), OutboxRelaySchedule.class.getDeclaredMethod("relay"));

    assertThat(predicate.test(SCHEDULED_TASK, relayPass)).isFalse();
  }

  @Test
  void anotherScheduledTaskIsObserved() throws NoSuchMethodException {
    var otherTask =
        new ScheduledTaskObservationContext(new Object(), Object.class.getMethod("hashCode"));

    assertThat(predicate.test(SCHEDULED_TASK, otherTask)).isTrue();
  }

  @Test
  void anyOtherObservationIsObserved() {
    assertThat(predicate.test("outbox.relay", new Observation.Context())).isTrue();
  }
}
