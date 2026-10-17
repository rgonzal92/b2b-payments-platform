package dev.rgonz.payments.platform.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.shared.Money;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Grafana's Breaks dashboard reads {@code reconciliation.breaks.open}: the open Breaks by kind and
 * by how long ago they were raised, on PostgreSQL. Other tests raise Breaks too, so each test
 * compares the gauges before and after its own.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OpenBreaksMetricTests {

  @Autowired MeterRegistry meters;
  @Autowired Breaks breaks;
  @Autowired OpenBreaksMetric metric;
  @Autowired JdbcClient jdbc;

  @Test
  void everyKindAndAgeIsRegisteredFromStartup() {
    for (var kind : BreakKind.values()) {
      for (var age : new String[] {"under_1_day", "1_to_7_days", "over_7_days"}) {
        assertThat(
                meters
                    .find("reconciliation.breaks.open")
                    .tag("kind", kind.name())
                    .tag("age", age)
                    .gauge())
            .as("%s %s", kind, age)
            .isNotNull();
      }
    }
  }

  @Test
  void theGaugesCountTheOpenBreaksOfEachKindByAge() {
    var kind = BreakKind.AFTER_HOLD_EXPIRED;
    metric.refresh();
    var underADay = open(kind, "under_1_day");
    var upToAWeek = open(kind, "1_to_7_days");
    var overAWeek = open(kind, "over_7_days");
    var otherKind = open(BreakKind.NO_AUTHORIZATION, "under_1_day");

    raise(kind, Duration.ZERO);
    raise(kind, Duration.ofHours(23));
    raise(kind, Duration.ofDays(3));
    raise(kind, Duration.ofDays(10));
    metric.refresh();

    assertThat(open(kind, "under_1_day")).isEqualTo(underADay + 2);
    assertThat(open(kind, "1_to_7_days")).isEqualTo(upToAWeek + 1);
    assertThat(open(kind, "over_7_days")).isEqualTo(overAWeek + 1);
    assertThat(open(BreakKind.NO_AUTHORIZATION, "under_1_day")).isEqualTo(otherKind);
  }

  private double open(BreakKind kind, String age) {
    return meters
        .get("reconciliation.breaks.open")
        .tag("kind", kind.name())
        .tag("age", age)
        .gauge()
        .value();
  }

  private void raise(BreakKind kind, Duration ago) {
    var recordId = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 19);
    breaks.raise(
        new Break(
            kind,
            recordId,
            LocalDate.of(2026, 10, 1),
            null,
            new Money(100, Currency.getInstance("USD")),
            Map.of()));
    jdbc.sql(
            """
            update reconciliation.break set created_at = now() - :seconds * interval '1 second'
             where record_id = :recordId
            """)
        .param("seconds", ago.toSeconds())
        .param("recordId", recordId)
        .update();
  }
}
