package dev.rgonz.payments.platform;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * A real Keycloak importing the repository's realm file, started once and shared by every test
 * class that needs real tokens. Every client's secret is the same test-only value.
 */
final class TestKeycloak {

  private static final String CLIENT_SECRET = "test-only-secret";

  private static final KeycloakContainer keycloak =
      // Same image as compose.yaml: change both together.
      new KeycloakContainer("quay.io/keycloak/keycloak:26.8.1")
          .withRealmImportFile("/keycloak/payments-realm.json")
          .withEnv("SIMULATOR_ISSUER_ADMIN_SECRET", CLIENT_SECRET)
          .withEnv("ACME_ERP_SECRET", CLIENT_SECRET)
          .withEnv("ACME_APPROVER_1_SECRET", CLIENT_SECRET)
          .withEnv("ACME_APPROVER_2_SECRET", CLIENT_SECRET);

  // Testcontainers stops it when the test JVM exits.
  static {
    keycloak.start();
  }

  private TestKeycloak() {}

  /** The realm's issuer, for {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}. */
  static String issuerUri() {
    return keycloak.getAuthServerUrl() + "/realms/payments";
  }

  /** An access token for the realm's client, signed in with client credentials. */
  static String clientCredentialsToken(String clientId) {
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", clientId);
    form.add("client_secret", CLIENT_SECRET);
    Map<?, ?> token =
        RestClient.create()
            .post()
            .uri(issuerUri() + "/protocol/openid-connect/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class);
    return (String) token.get("access_token");
  }
}
