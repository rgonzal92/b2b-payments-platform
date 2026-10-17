package dev.rgonz.payments.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dashboards and the alert rule that compose.yaml mounts into the otel-lgtm container parse,
 * and query only metrics the platform exports, under the names Prometheus gives them over OTLP (a
 * counter gains {@code _total}, a timer {@code _milliseconds_bucket}, {@code _count} and {@code
 * _sum}).
 */
class GrafanaProvisioningTests {

  // Relative to the platform module, where the tests run.
  private static final Path GRAFANA = Path.of("..", "grafana");

  private static final Set<String> EXPORTED_METRICS =
      Set.of(
          // Spring gRPC's grpc.server observation, as a histogram (application.properties).
          "grpc_server_milliseconds_bucket",
          "grpc_server_milliseconds_count",
          "grpc_server_milliseconds_sum",
          // authorization: IssuerAuthorizationService.
          "authorization_answers_total",
          // payables: Payments and PaymentSaga.
          "payments_state_changes_total",
          "payments_steps_milliseconds_bucket",
          "payments_steps_milliseconds_count",
          "payments_steps_milliseconds_sum",
          // clearing: ClearingFileImports.
          "clearing_files_total",
          "clearing_clearings_total",
          "clearing_settlements_total",
          // reconciliation: OpenBreaksMetric, a gauge, so no suffix.
          "reconciliation_breaks_open");

  // A metric name in PromQL is followed by its label matchers or its range: name{…} or name[…].
  private static final Pattern METRIC = Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:]*)\\s*[{\\[]");

  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void everyDashboardQueriesOnlyExportedMetrics() throws IOException {
    for (var name : List.of("authorization", "payments", "clearing", "breaks")) {
      var dashboard = json.readTree(GRAFANA.resolve("dashboards").resolve(name + ".json").toFile());

      assertThat(dashboard.path("uid").asString()).as(name).isEqualTo(name);
      assertThat(dashboard.path("title").asString()).as(name).isNotBlank();
      var expressions = new ArrayList<String>();
      for (var panel : dashboard.path("panels")) {
        for (var target : panel.path("targets")) {
          assertThat(target.path("datasource").path("uid").asString())
              .as("%s: %s", name, panel.path("title").asString())
              .isEqualTo("prometheus");
          // A provisioned query that says neither showed "No data" in a time series panel.
          assertThat(
                  target.path("range").asBoolean(false) ^ target.path("instant").asBoolean(false))
              .as(
                  "%s: %s is either a range or an instant query",
                  name, panel.path("title").asString())
              .isTrue();
          expressions.add(target.path("expr").asString());
        }
      }
      assertThat(expressions).as(name).isNotEmpty();
      assertThat(expressions.stream().flatMap(GrafanaProvisioningTests::metricsIn))
          .as(name)
          .isNotEmpty()
          .isSubsetOf(EXPORTED_METRICS);
    }
  }

  @Test
  void theDashboardProviderReadsEveryDashboardFromTheFolderComposeMounts() throws IOException {
    Map<String, Object> provider = yaml(GRAFANA.resolve("provisioning/dashboards.yaml"));

    var providers = list(provider.get("providers"));
    assertThat(providers).hasSize(1);
    var options = map(map(providers.getFirst()).get("options"));
    assertThat(options.get("path")).isEqualTo("/otel-lgtm/b2b-payments-dashboards");
    assertThat(Files.readString(Path.of("..", "compose.yaml")))
        .contains("./grafana/dashboards:/otel-lgtm/b2b-payments-dashboards:ro");
  }

  // ADR-0010: 99% of Authorizations within 100 ms. The rule fires once Authorize's p99 has stayed
  // above 100 ms for 5 minutes.
  @Test
  void theAlertRuleFiresWhenAuthorizationsP99StaysAbove100MsFor5Minutes() throws IOException {
    Map<String, Object> alerting = yaml(GRAFANA.resolve("provisioning/alerting.yaml"));

    var rules = list(map(list(alerting.get("groups")).getFirst()).get("rules"));
    assertThat(rules).hasSize(1);
    var rule = map(rules.getFirst());
    assertThat(rule.get("for")).isEqualTo("5m");
    var data = list(rule.get("data")).stream().map(GrafanaProvisioningTests::map).toList();
    assertThat(data.getFirst()).containsEntry("datasourceUid", "prometheus");
    var query = map(data.getFirst().get("model"));
    assertThat((String) query.get("expr"))
        .contains("histogram_quantile(0.99")
        .contains("rpc_method=\"Authorize\"");
    assertThat(metricsIn((String) query.get("expr"))).isNotEmpty().isSubsetOf(EXPORTED_METRICS);
    var threshold =
        data.stream()
            .map(step -> map(step.get("model")))
            .filter(model -> "threshold".equals(model.get("type")))
            .findFirst()
            .orElseThrow();
    var evaluator = map(map(list(threshold.get("conditions")).getFirst()).get("evaluator"));
    assertThat(evaluator).containsEntry("type", "gt").containsEntry("params", List.of(100));
    assertThat(rule.get("condition")).isEqualTo(threshold.get("refId"));
  }

  private static Stream<String> metricsIn(String expression) {
    return METRIC.matcher(expression).results().map(match -> match.group(1));
  }

  private static Map<String, Object> yaml(Path file) throws IOException {
    try (var reader = Files.newBufferedReader(file)) {
      return new Yaml().load(reader);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }
}
