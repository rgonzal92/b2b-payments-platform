package dev.rgonz.payments.platform.buyers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import dev.rgonz.payments.platform.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BuyersApiTests {

  @Autowired MockMvcTester mvc;

  @Test
  void issuerStaffCreateABuyer() {
    var buyerId = UUID.randomUUID();

    var response = put(buyerId, ISSUER_ADMIN, buyerJson("Acme", 1_000_000));

    assertThat(response).hasStatus(201);
    assertThat(response)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"id": "%s", "name": "Acme", "approvalThreshold": {"minorUnits": 1000000, "currency": "USD"}}
            """
                .formatted(buyerId));
  }

  @Test
  void repeatingTheSamePutIsSafe() {
    var buyerId = UUID.randomUUID();
    put(buyerId, ISSUER_ADMIN, buyerJson("Acme", 1_000_000));

    var response = put(buyerId, ISSUER_ADMIN, buyerJson("Acme", 1_000_000));

    assertThat(response).hasStatus(200);
    assertThat(response).bodyJson().extractingPath("$.name").isEqualTo("Acme");
  }

  @Test
  void aDifferentBuyerUnderAnExistingIdIsAConflict() {
    var buyerId = UUID.randomUUID();
    put(buyerId, ISSUER_ADMIN, buyerJson("Acme", 1_000_000));

    var response = put(buyerId, ISSUER_ADMIN, buyerJson("Acme Corp", 2_500_000));

    assertThat(response).hasStatus(409);
    assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
  }

  @Test
  void onlyIssuerStaffMayCreateBuyers() {
    var response = put(UUID.randomUUID(), "ROLE_erp", buyerJson("Acme", 1_000_000));

    assertThat(response).hasStatus(403);
    assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(response).bodyJson().extractingPath("$.status").isEqualTo(403);
  }

  @Test
  void requestsWithoutATokenAreRejected() {
    var response =
        mvc.put()
            .uri("/v1/buyers/{buyerId}", UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content(buyerJson("Acme", 1_000_000))
            .exchange();

    assertThat(response).hasStatus(401);
    assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(response).headers().containsHeader("WWW-Authenticate");
  }

  @Test
  void invalidBuyersAreRejectedAsProblems() {
    var blankName = put(UUID.randomUUID(), ISSUER_ADMIN, buyerJson("", 1_000_000));
    var spacesOnly = put(UUID.randomUUID(), ISSUER_ADMIN, buyerJson("   ", 1_000_000));
    var negativeThreshold = put(UUID.randomUUID(), ISSUER_ADMIN, buyerJson("Acme", -1));
    var otherCurrency =
        put(
            UUID.randomUUID(),
            ISSUER_ADMIN,
            """
            {"name": "Acme", "approvalThreshold": {"minorUnits": 100, "currency": "EUR"}}
            """);

    for (var response :
        java.util.List.of(blankName, spacesOnly, negativeThreshold, otherCurrency)) {
      assertThat(response).hasStatus(400);
      assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }
  }

  private static final String ISSUER_ADMIN = "ROLE_issuer-admin";

  private MvcTestResult put(UUID buyerId, String authority, String body) {
    return mvc.put()
        .uri("/v1/buyers/{buyerId}", buyerId)
        .with(jwt().authorities(new SimpleGrantedAuthority(authority)))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  private static String buyerJson(String name, long thresholdMinorUnits) {
    return """
        {"name": "%s", "approvalThreshold": {"minorUnits": %d, "currency": "USD"}}
        """
        .formatted(name, thresholdMinorUnits);
  }
}
