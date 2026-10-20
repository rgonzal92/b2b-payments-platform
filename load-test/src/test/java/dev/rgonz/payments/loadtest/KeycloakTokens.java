package dev.rgonz.payments.loadtest;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * A Keycloak client's access token, by client credentials, for a simulation to send with every
 * call.
 */
final class KeycloakTokens {

  // Keycloak's tokens last 5 minutes, and a run uses the one it starts with.
  private static final int LONGEST_RUN_SECONDS = 240;

  // On the host by default; inside the cluster, Keycloak's Service. Either way the token names
  // http://localhost:8180 as its issuer, which the apps check.
  private static final String TOKEN_URI =
      System.getProperty(
          "tokenUri", "http://localhost:8180/realms/payments/protocol/openid-connect/token");
  private static final Pattern ACCESS_TOKEN =
      Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

  private KeycloakTokens() {}

  /** Refuses a run of {@code runSeconds} that would outlive the token it starts with. */
  static void requireRunWithinTokenLife(int runSeconds) {
    if (runSeconds > LONGEST_RUN_SECONDS) {
      throw new IllegalStateException(
          "A run longer than %d s would outlive its token.".formatted(LONGEST_RUN_SECONDS));
    }
  }

  /**
   * The client's token; its secret comes from the environment variable {@code secretVariable}, as
   * in {@code .env}, and is never printed.
   */
  static String clientCredentials(String clientId, String secretVariable)
      throws IOException, InterruptedException {
    var secret = System.getenv(secretVariable);
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException(
          "Set %s, from .env, to sign in as %s.".formatted(secretVariable, clientId));
    }
    var form =
        "grant_type=client_credentials&client_id="
            + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
            + "&client_secret="
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
            "Keycloak answered %d for %s's token.".formatted(response.statusCode(), clientId));
      }
      return token.group(1);
    }
  }
}
