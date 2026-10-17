package dev.rgonz.payments.platform.reconciliation;

import dev.rgonz.payments.platform.reconciliation.Breaks.OpenAge;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The open Breaks by kind and age, as the gauge {@code reconciliation.breaks.open} (tags {@code
 * kind} and {@code age}), for Grafana's Breaks dashboard. A gauge read from the table, not a pair
 * of counters raised and resolved: the table is the truth across restarts, where counters start
 * again at zero, and only it knows each Break's age. One grouped query every 30 seconds, rather
 * than one per series on every export.
 */
@Component
class OpenBreaksMetric {

  private final Breaks breaks;
  private final Map<BreakKind, Map<OpenAge, AtomicLong>> open = new EnumMap<>(BreakKind.class);

  OpenBreaksMetric(Breaks breaks, MeterRegistry meters) {
    this.breaks = breaks;
    // Every series exists from startup, at zero until the first refresh.
    for (var kind : BreakKind.values()) {
      var byAge = new EnumMap<OpenAge, AtomicLong>(OpenAge.class);
      for (var age : OpenAge.values()) {
        var count = new AtomicLong();
        byAge.put(age, count);
        Gauge.builder("reconciliation.breaks.open", count, AtomicLong::get)
            .description("Open Breaks, by kind and by how long ago they were raised")
            .tag("kind", kind.name())
            .tag("age", age.tag)
            .register(meters);
      }
      open.put(kind, byAge);
    }
  }

  /** Reads the open Breaks' counts into the gauges; a series no open Break has goes to zero. */
  @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
  void refresh() {
    var counts = breaks.countOpen();
    open.forEach(
        (kind, byAge) ->
            byAge.forEach(
                (age, count) ->
                    count.set(counts.getOrDefault(kind, Map.of()).getOrDefault(age, 0L))));
  }
}
