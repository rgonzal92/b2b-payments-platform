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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dashboards and the alert rule that compose.yaml mounts into the otel-lgtm container parse,
 * and query only metrics the platform, authorization-service or the Gateway exports, under the
 * names Prometheus gives them over OTLP (a counter gains {@code _total}, a timer {@code
 * _milliseconds_bucket}, {@code _count} and {@code _sum}).
 */
class GrafanaProvisioningTests {

  // Relative to the platform module, where the tests run.
  private static final Path GRAFANA = Path.of("..", "grafana");

  private static final Set<String> EXPORTED_METRICS =
      Set.of(
          // authorization-service's since the cutover, under the platform's old names: Spring
          // gRPC's grpc.server observation, as a histogram, and its IssuerAuthorizationService's
          // answers.
          "grpc_server_milliseconds_bucket",
          "grpc_server_milliseconds_count",
          "grpc_server_milliseconds_sum",
          "authorization_answers_total",
          // Both apps' Kafka consumers, through Spring Boot's Kafka client metrics: each
          // partition's lag behind the topic's end, labelled by topic and client_id.
          "kafka_consumer_fetch_manager_records_lag",
          // Every app's OTLP resource, one series per app, refreshed by each metrics export.
          "target_info",
          // payables: Payments and PaymentSaga, and WaitingPaymentsMetric's gauges (the oldest
          // wait has the base unit seconds, so it gains _seconds).
          "payments_state_changes_total",
          "payments_waiting_card_on_file",
          "payments_waiting_card_on_file_oldest_seconds",
          "payments_steps_milliseconds_bucket",
          "payments_steps_milliseconds_count",
          "payments_steps_milliseconds_sum",
          // clearing: ClearingFileImports.
          "clearing_files_total",
          "clearing_clearings_total",
          "clearing_settlements_total",
          // reconciliation: OpenBreaksMetric, a gauge, so no suffix.
          "reconciliation_breaks_open",
          // The Gateway's RateLimits: requests let through unlimited because Redis failed.
          "gateway_rate_limiter_failed_open_total");

  // A metric name in PromQL is followed by its label matchers or its range: name{…} or name[…].
  private static final Pattern METRIC = Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:]*)\\s*[{\\[]");

  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void everyDashboardQueriesOnlyExportedMetrics() throws IOException {
    for (var name : List.of("authorization", "payments", "clearing", "breaks")) {
      var dashboard = dashboard(name);

      assertThat(dashboard.path("uid").asString()).as(name).isEqualTo(name);
      assertThat(dashboard.path("title").asString()).as(name).isNotBlank();
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
        }
      }
      assertThat(expressions(name).stream().flatMap(GrafanaProvisioningTests::metricsIn))
          .as(name)
          .isNotEmpty()
          .isSubsetOf(EXPORTED_METRICS);
    }
  }

  // Since the cutover only authorization-service answers the Network (#96).
  @Test
  void theAuthorizationDashboardReadsAuthorizationService() throws IOException {
    var authorizationQueries =
        expressions("authorization").stream()
            .filter(expr -> expr.contains("grpc_server") || expr.contains("authorization_answers"))
            .toList();

    assertThat(authorizationQueries)
        .isNotEmpty()
        .allSatisfy(expr -> assertThat(expr).contains("job=\"authorization-service\""));
  }

  @Test
  void theAuthorizationDashboardShowsConsumerLagOnBothTopics() throws IOException {
    assertThat(expressions("authorization"))
        .anySatisfy(
            expr ->
                assertThat(expr)
                    .contains(
                        "kafka_consumer_fetch_manager_records_lag{job=\"authorization-service\","
                            + " topic=\"cards\"}"))
        .anySatisfy(
            expr ->
                assertThat(expr)
                    .contains(
                        "kafka_consumer_fetch_manager_records_lag{job=\"platform\","
                            + " topic=\"authorizations\"}"));
  }

  @Test
  void thePaymentsDashboardShowsPaymentsWaitingForTheirCardToBeOnFile() throws IOException {
    assertThat(expressions("payments").stream().flatMap(GrafanaProvisioningTests::metricsIn))
        .contains("payments_waiting_card_on_file", "payments_waiting_card_on_file_oldest_seconds");
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
    var rule = rule("authorization-p99");

    assertThat(rule.get("for")).isEqualTo("5m");
    assertThat(queryOf(rule))
        .contains("histogram_quantile(0.99")
        .contains("rpc_method=\"Authorize\"")
        .contains("job=\"authorization-service\"");
    assertThat(thresholdOf(rule)).containsEntry("type", "gt").containsEntry("params", List.of(100));
  }

  // M5 design, "The gaps": a Payment waits for its card to be on file without a limit, and Grafana
  // alerts once one has waited more than 5 minutes.
  @Test
  void theAlertRuleFiresWhenAPaymentHasWaitedMoreThan5MinutesForItsCardToBeOnFile()
      throws IOException {
    var rule = rule("payment-waiting-card-on-file");

    assertThat(queryOf(rule)).contains("payments_waiting_card_on_file_oldest_seconds");
    assertThat(thresholdOf(rule)).containsEntry("type", "gt").containsEntry("params", List.of(300));
  }

  // The p99 rule sees no data when the service is down, as when the Network is idle: a rule per app
  // fires once it has exported nothing for 5 minutes. Each export refreshes the app's target_info.
  @ParameterizedTest
  @ValueSource(strings = {"authorization-service", "platform"})
  void theAlertRuleFiresWhenAnAppHasExportedNothingFor5Minutes(String app) throws IOException {
    var rule = rule(app + "-not-exporting");

    assertThat(queryOf(rule)).isEqualTo("absent_over_time(target_info{job=\"" + app + "\"}[5m])");
    assertThat(thresholdOf(rule)).containsEntry("type", "gt").containsEntry("params", List.of(0));
    assertThat(rule.get("noDataState")).as("present: nothing absent").isEqualTo("OK");
  }

  // ADR-0017: with Redis down the Gateway lets requests through unlimited and Grafana alerts once
  // that has gone on for 2 minutes, so one slow answer from Redis doesn't page anyone.
  @Test
  void theAlertRuleFiresWhenTheGatewayLetsRequestsThroughUnlimitedFor2Minutes() throws IOException {
    var rule = rule("rate-limiter-redis-down");

    assertThat(queryOf(rule))
        .contains("gateway_rate_limiter_failed_open_total")
        .contains("job=\"gateway\"");
    assertThat(rule.get("for")).isEqualTo("2m");
    assertThat(thresholdOf(rule)).containsEntry("type", "gt").containsEntry("params", List.of(0));
    assertThat(map(rule.get("annotations")).get("summary"))
        .asString()
        .contains("docs/runbooks/rate-limiter-redis-down.md");
  }

  // The provisioned rule with this uid, from any group.
  private static Map<String, Object> rule(String uid) throws IOException {
    Map<String, Object> alerting = yaml(GRAFANA.resolve("provisioning/alerting.yaml"));
    return list(alerting.get("groups")).stream()
        .flatMap(group -> list(map(group).get("rules")).stream())
        .map(GrafanaProvisioningTests::map)
        .filter(rule -> uid.equals(rule.get("uid")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No alert rule " + uid));
  }

  // The rule's Prometheus query, which names only exported metrics.
  private static String queryOf(Map<String, Object> rule) {
    var first = map(list(rule.get("data")).getFirst());
    assertThat(first).containsEntry("datasourceUid", "prometheus");
    var expr = (String) map(first.get("model")).get("expr");
    assertThat(metricsIn(expr)).isNotEmpty().isSubsetOf(EXPORTED_METRICS);
    return expr;
  }

  // The evaluator of the rule's threshold step, which must be the rule's condition.
  private static Map<String, Object> thresholdOf(Map<String, Object> rule) {
    var threshold =
        list(rule.get("data")).stream()
            .map(step -> map(map(step).get("model")))
            .filter(model -> "threshold".equals(model.get("type")))
            .findFirst()
            .orElseThrow();
    assertThat(rule.get("condition")).isEqualTo(threshold.get("refId"));
    return map(map(list(threshold.get("conditions")).getFirst()).get("evaluator"));
  }

  private JsonNode dashboard(String name) throws IOException {
    return json.readTree(GRAFANA.resolve("dashboards").resolve(name + ".json").toFile());
  }

  // Every query of the dashboard's panels.
  private List<String> expressions(String dashboard) throws IOException {
    var expressions = new ArrayList<String>();
    for (var panel : dashboard(dashboard).path("panels")) {
      for (var target : panel.path("targets")) {
        expressions.add(target.path("expr").asString());
      }
    }
    return expressions;
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
