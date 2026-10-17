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
import dev.rgonz.payments.loadtest.CardsFile.Card;
import io.gatling.javaapi.core.ActionBuilder;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.grpc.Status;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The platform's {@code Authorize} under load (M4 design, "The load test"; ADR-0010): an open
 * workload that ramps up to 200 Authorizations a second over a 30-second warm-up, then holds 200 a
 * second for 2 minutes. About 90% charge a fresh card from the simulator's cards file and expect it
 * approved; 5% charge a card this run already charged (a {@code 57}) and 5% send a wrong security
 * code (an {@code N7}). Any other answer, or any gRPC error, fails the call, and one failed call
 * fails the run. The warm-up and the measured window are separate groups in the report.
 *
 * <p>Runs by hand against the Docker Compose stack: see the README. {@code NETWORK_CLIENT_SECRET}
 * in the environment signs it in as the Network. System properties override the defaults: {@code
 * cardsFile} (the pom passes the repository's {@code var/load-test/cards.csv}), {@code rate} (200),
 * {@code warmUpSeconds} (30) and {@code measuredSeconds} (120).
 */
public class AuthorizeSimulation extends Simulation {

  private static final String APPROVED = "00";
  private static final String CARD = "card";
  private static final String TOKEN_URI =
      "http://localhost:8180/realms/payments/protocol/openid-connect/token";
  private static final Pattern ACCESS_TOKEN =
      Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
  // Keycloak's tokens last 5 minutes, and the run uses the one it starts with.
  private static final int LONGEST_RUN_SECONDS = 240;

  private static final Path CARDS_FILE =
      Path.of(System.getProperty("cardsFile")).toAbsolutePath().normalize();
  private static final int RATE = Integer.getInteger("rate", 200);
  private static final int WARM_UP_SECONDS = Integer.getInteger("warmUpSeconds", 30);
  private static final int MEASURED_SECONDS = Integer.getInteger("measuredSeconds", 120);

  /** The two kinds of decline, each on a card this run already charged. */
  private enum Decline {
    SECOND_CHARGE("second charge", "57", false),
    // The platform checks the security code before the card's status.
    WRONG_SECURITY_CODE("wrong security code", "N7", true);

    final String requestName;
    final String responseCode;
    final boolean wrongSecurityCode;

    Decline(String requestName, String responseCode, boolean wrongSecurityCode) {
      this.requestName = requestName;
      this.responseCode = responseCode;
      this.wrongSecurityCode = wrongSecurityCode;
    }
  }

  // Fresh cards, each charged once; then the cards this run charged, for the declines.
  private final Queue<Card> freshCards;
  private final Queue<Card> chargedCards = new ConcurrentLinkedQueue<>();

  public AuthorizeSimulation() throws IOException, InterruptedException {
    if (WARM_UP_SECONDS + MEASURED_SECONDS > LONGEST_RUN_SECONDS) {
      throw new IllegalStateException(
          "A run longer than %d s would outlive its Network token.".formatted(LONGEST_RUN_SECONDS));
    }
    freshCards = CardsFile.read(CARDS_FILE);
    // About 90% of users charge a fresh card, plus a margin for the random mix and for the first
    // declines, which charge a fresh card while none is charged yet.
    var users = (1 + RATE) * WARM_UP_SECONDS / 2 + RATE * MEASURED_SECONDS;
    var needed = users * 91 / 100;
    if (freshCards.size() < needed) {
      throw new IllegalStateException(
          "%s holds %d cards; this run needs about %d. Seed more with POST /demo/load-test-cards."
              .formatted(CARDS_FILE, freshCards.size(), needed));
    }
    var protocol =
        grpc.serverConfigurations(
            grpc.serverConfiguration("platform")
                .forAddress("localhost", 9090)
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
    var approved = approvedCharge(phase);
    return scenario(phase)
        .group(phase)
        .on(
            randomSwitch()
                .on(
                    percent(90).then(approved),
                    percent(5).then(declinedCharge(phase, Decline.SECOND_CHARGE, approved)),
                    percent(5).then(declinedCharge(phase, Decline.WRONG_SECURITY_CODE, approved))));
  }

  // A fresh card's first Charge, approved; the card then serves the declines.
  private ChainBuilder approvedCharge(String phase) {
    return takeCard(freshCards::poll)
        .doIfOrElse(session -> session.contains(CARD))
        .then(
            exec(authorize(phase + ": approved charge", APPROVED, false))
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
  // charged, an approved Charge instead.
  private ChainBuilder declinedCharge(String phase, Decline decline, ChainBuilder approved) {
    return takeCard(
            () -> {
              var card = chargedCards.poll();
              if (card != null) {
                chargedCards.add(card);
              }
              return card;
            })
        .doIfOrElse(session -> session.contains(CARD))
        .then(
            exec(
                authorize(
                    phase + ": " + decline.requestName,
                    decline.responseCode,
                    decline.wrongSecurityCode)))
        .orElse(approved);
  }

  // Puts the card the function hands out in the session, or none when it hands out null.
  private ChainBuilder takeCard(Supplier<Card> next) {
    return exec(
        session -> {
          var card = next.get();
          return card == null ? session.remove(CARD) : session.set(CARD, card);
        });
  }

  private static ActionBuilder authorize(
      String name, String responseCode, boolean wrongSecurityCode) {
    return grpc(name)
        .unary(IssuerAuthorizationGrpc.getAuthorizeMethod())
        .send(session -> CardsFile.charge(card(session), wrongSecurityCode))
        .check(
            statusCode().is(Status.Code.OK),
            response(AuthorizationResponse::getResponseCode).is(responseCode));
  }

  private static Card card(Session session) {
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
        throw new IOException(
            "Keycloak answered " + response.statusCode() + " for the Network's token.");
      }
      return token.group(1);
    }
  }
}
