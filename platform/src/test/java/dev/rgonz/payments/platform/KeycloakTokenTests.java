package dev.rgonz.payments.platform;

import static org.assertj.core.api.Assertions.assertThat;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Checks the repository's realm file against a real Keycloak: secrets, the roles claim, the issuer.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class KeycloakTokenTests {

  private static final String CLIENT_SECRET = "test-only-secret";

  @Container
  static final KeycloakContainer keycloak =
      // Same image as compose.yaml: change both together.
      new KeycloakContainer("quay.io/keycloak/keycloak:26.8.1")
          .withRealmImportFile("/keycloak/payments-realm.json")
          .withEnv("SIMULATOR_ISSUER_ADMIN_SECRET", CLIENT_SECRET);

  @DynamicPropertySource
  static void issuer(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
        () -> keycloak.getAuthServerUrl() + "/realms/payments");
  }

  @Autowired MockMvcTester mvc;

  @Test
  void aKeycloakTokenForIssuerStaffCreatesABuyer() {
    var response =
        mvc.put()
            .uri("/v1/buyers/{buyerId}", UUID.randomUUID())
            .header("Authorization", "Bearer " + clientCredentialsToken("simulator-issuer-admin"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name": "Acme", "approvalThreshold": {"minorUnits": 1000000, "currency": "USD"}}
                """)
            .exchange();

    assertThat(response).hasStatus(201);
  }

  private static String clientCredentialsToken(String clientId) {
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", clientId);
    form.add("client_secret", CLIENT_SECRET);
    Map<?, ?> token =
        RestClient.create()
            .post()
            .uri(keycloak.getAuthServerUrl() + "/realms/payments/protocol/openid-connect/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class);
    return (String) token.get("access_token");
  }
}
