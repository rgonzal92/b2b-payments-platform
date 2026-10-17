package dev.rgonz.payments.loadtest;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.crashLoadGenerator;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.randomSwitch;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.grpc.GrpcDsl.grpc;
import static io.gatling.javaapi.grpc.GrpcDsl.response;
import static io.gatling.javaapi.grpc.GrpcDsl.statusCode;

import dev.rgonz.payments.contracts.network.AuthorizationResponse;
import dev.rgonz.payments.contracts.network.IssuerAuthorizationGrpc;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.grpc.Status;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/**
 * The platform's {@code Authorize} under load (M4 design, "The load test"; ADR-0010): an open
 * workload that ramps up to 200 Charges a second over a 30-second warm-up, then holds 200 a second
 * for 2 minutes. About 90% charge a fresh card from the simulator's cards file and expect an
 * approval; 5% charge a card this run already charged (a {@code 57}) and 5% send a wrong security
 * code (an {@code N7}). Any other answer, or any gRPC error, fails the call, and one failed call
 * fails the run. The warm-up and the measured window are separate groups in the report.
 *
 * <p>Runs by hand against the Docker Compose stack: see the README. {@code NETWORK_CLIENT_SECRET}
 * in the environment signs it in as the Network; system properties {@code cardsFile}, {@code rate},
 * {@code warmUpSeconds} and {@code measuredSeconds} override the defaults.
 */
public class AuthorizeSimulation extends Simulation {

  private static final String APPROVED = "00";
  private static final String NOT_PERMITTED = "57";
  private static final String SECURITY_CODE_MISMATCH = "N7";
  private static final String CARD = "card";

  // The pom passes the repository's var/load-test/cards.csv unless told otherwise.
  private static final Path CARDS_FILE =
      Path.of(System.getProperty("cardsFile")).toAbsolutePath().normalize();
  private static final int RATE = Integer.getInteger("rate", 200);
  private static final int WARM_UP_SECONDS = Integer.getInteger("warmUpSeconds", 30);
  private static final int MEASURED_SECONDS = Integer.getInteger("measuredSeconds", 120);
  private static final String TOKEN_URI =
      System.getProperty(
          "tokenUri", "http://localhost:8180/realms/payments/protocol/openid-connect/token");
  private static final Pattern ACCESS_TOKEN =
      Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

  // Fresh cards, each charged once; then the cards this run charged, for the declines.
  private final Queue<Map<String, Object>> freshCards;
  private final Queue<Map<String, Object>> chargedCards = new ConcurrentLinkedQueue<>();

  public AuthorizeSimulation() throws IOException, InterruptedException {
    freshCards = Cards.read(CARDS_FILE);
    // About 90% of users charge a fresh card; the declines reuse cards this run charged.
    var users = RATE * WARM_UP_SECONDS / 2 + RATE * MEASURED_SECONDS;
    var needed = users * 9 / 10;
    if (freshCards.size() < needed) {
      throw new IllegalStateException(
          "%s holds %d cards; this run charges about %d. Seed more with POST /demo/load-test-cards."
              .formatted(CARDS_FILE.toAbsolutePath(), freshCards.size(), needed));
    }
    // One token for the whole run, which takes under 3 minutes: Keycloak's tokens last 5.
    var protocol =
        grpc.serverConfigurations(
            grpc.serverConfiguration("platform")
                .forAddress(
                    System.getProperty("platformHost", "localhost"),
                    Integer.getInteger("platformPort", 9090))
                .usePlaintext()
                // One channel for every user, as the Network keeps one connection to the Issuer:
                // Gatling's default, a channel per user, would time each user's connection set-up.
                .shareChannel()
                .asciiHeader("authorization")
                .value("Bearer " + networkToken()));

    setUp(
            charges("Warm-up")
                .injectOpen(rampUsersPerSec(1).to(RATE).during(WARM_UP_SECONDS))
                .andThen(
                    charges("Measured")
                        .injectOpen(constantUsersPerSec(RATE).during(MEASURED_SECONDS))))
        .protocols(protocol)
        .assertions(global().failedRequests().count().is(0L));
  }

  // Each user makes one Charge, inside a group named after the phase, so the report shows the
  // measured window's numbers apart from the warm-up's.
  private ScenarioBuilder charges(String phase) {
    var approval = approval(phase);
    return scenario(phase)
        .group(phase)
        .on(
            randomSwitch()
                .on(
                    percent(90).then(approval),
                    percent(5)
                        .then(decline(phase, "second charge", NOT_PERMITTED, false, approval)),
                    percent(5)
                        .then(
                            decline(
                                phase,
                                "wrong security code",
                                SECURITY_CODE_MISMATCH,
                                true,
                                approval))));
  }

  // A fresh card's first Charge, approved; the card then serves the declines.
  private ChainBuilder approval(String phase) {
    return exec(session -> {
          var card = freshCards.poll();
          return card == null ? session.remove(CARD) : session.set(CARD, card);
        })
        .doIfOrElse(session -> session.contains(CARD))
        .then(
            exec(authorize(phase + ": approval", APPROVED, false))
                .exec(
                    session -> {
                      if (!session.isFailed()) {
                        chargedCards.add(card(session));
                      }
                      return session;
                    }))
        .orElse(crashLoadGenerator("The cards file ran out of fresh cards."));
  }

  // A Charge on a card this run already charged, rotating through them; before the first is
  // charged, an approval instead.
  private ChainBuilder decline(
      String phase,
      String kind,
      String responseCode,
      boolean wrongSecurityCode,
      ChainBuilder approval) {
    return exec(session -> {
          var card = chargedCards.poll();
          if (card == null) {
            return session.remove(CARD);
          }
          chargedCards.add(card);
          return session.set(CARD, card);
        })
        .doIfOrElse(session -> session.contains(CARD))
        .then(exec(authorize(phase + ": " + kind, responseCode, wrongSecurityCode)))
        .orElse(approval);
  }

  private static io.gatling.javaapi.core.ActionBuilder authorize(
      String name, String responseCode, boolean wrongSecurityCode) {
    return grpc(name)
        .unary(IssuerAuthorizationGrpc.getAuthorizeMethod())
        .send(session -> Cards.charge(card(session), wrongSecurityCode))
        .check(
            statusCode().is(Status.Code.OK),
            response(AuthorizationResponse::getResponseCode).is(responseCode));
  }

  private static Map<String, Object> card(Session session) {
    return session.get(CARD);
  }

  // The Network's token, by client credentials; the secret comes from the environment and is never
  // printed.
  private static String networkToken() throws IOException, InterruptedException {
    var secret = System.getenv("NETWORK_CLIENT_SECRET");
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException(
          "Set NETWORK_CLIENT_SECRET, from .env, to sign in as the Network.");
    }
    var form =
        "grant_type=client_credentials&client_id=network&client_secret="
            + URLEncoder.encode(secret, StandardCharsets.UTF_8);
    try (var http = HttpClient.newHttpClient()) {
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create(TOKEN_URI))
                  .timeout(Duration.ofSeconds(10))
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(HttpRequest.BodyPublishers.ofString(form))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      var token = ACCESS_TOKEN.matcher(response.body());
      if (response.statusCode() != 200 || !token.find()) {
        throw new UncheckedIOException(
            new IOException(
                "Keycloak answered " + response.statusCode() + " for the Network's token"));
      }
      return token.group(1);
    }
  }
}
