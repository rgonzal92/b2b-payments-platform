package dev.rgonz.payments.platform;

import org.springframework.context.ApplicationContext;

/**
 * Runs the event resubmission schedule's pass by hand, for tests in modules that can't wait a
 * minute for it. The schedule stays package-private.
 */
public final class EventResubmission {

  private EventResubmission() {}

  public static void runNow(ApplicationContext context) {
    context.getBean(EventResubmissionSchedule.class).resubmitIncomplete();
  }
}
