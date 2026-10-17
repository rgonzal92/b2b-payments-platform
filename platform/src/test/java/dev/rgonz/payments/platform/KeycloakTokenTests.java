package dev.rgonz.payments.platform;

import static dev.rgonz.payments.platform.TestKeycloak.ACME_ID;
import static dev.rgonz.payments.platform.TestKeycloak.clientCredentialsToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.JWTParser;
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
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Checks the repository's realm file against a real Keycloak: secrets, the roles claim, the issuer.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class KeycloakTokenTests {

  @DynamicPropertySource
  static void issuer(DynamicPropertyRegistry registry) {
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestKeycloak::issuerUri);
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

  @Test
  void acmesErpGetsATokenForAcmeAndSubmitsAnInvoice() throws Exception {
    createAcme();

    var token = clientCredentialsToken("acme-erp");
    var response = submitInvoiceForAcme(token, "INV-1");

    var claims = JWTParser.parse(token).getJWTClaimsSet();
    assertThat(claims.getStringClaim("buyer_id")).isEqualTo(ACME_ID);
    assertThat(claims.getStringListClaim("roles")).contains("erp");
    assertThat(response).hasStatus(201);
  }

  @Test
  void acmesApproverGetsATokenForAcmeAndApprovesAnInvoice() throws Exception {
    createAcme();
    var submitted = submitInvoiceForAcme(clientCredentialsToken("acme-erp"), "INV-2");
    String invoiceId = JsonPath.read(submitted.getResponse().getContentAsString(), "$.id");

    var token = clientCredentialsToken("acme-approver-1");
    var response =
        mvc.post()
            .uri("/v1/invoices/{invoiceId}/approval", invoiceId)
            .header("Authorization", "Bearer " + token)
            .exchange();

    var claims = JWTParser.parse(token).getJWTClaimsSet();
    assertThat(claims.getStringClaim("buyer_id")).isEqualTo(ACME_ID);
    assertThat(claims.getStringListClaim("roles")).contains("approver");
    assertThat(response).hasStatus(200);
    assertThat(response)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {
              "decisions": [
                {
                  "decision": "APPROVED",
                  "approverId": "%s",
                  "approverName": "service-account-acme-approver-1"
                }
              ]
            }
            """
                .formatted(claims.getSubject()));
  }

  @Test
  void theSimulatorsReconcilerGetsATokenWithTheReconcilerRole() throws Exception {
    var claims = JWTParser.parse(clientCredentialsToken("simulator-reconciler")).getJWTClaimsSet();

    assertThat(claims.getStringListClaim("roles")).contains("reconciler");
    assertThat(claims.getStringListClaim("roles")).doesNotContain("issuer-admin");
  }

  // Both tests need Acme; whichever runs second finds it already there.
  private void createAcme() {
    var response =
        mvc.put()
            .uri("/v1/buyers/{buyerId}", ACME_ID)
            .header("Authorization", "Bearer " + clientCredentialsToken("simulator-issuer-admin"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name": "Acme", "approvalThreshold": {"minorUnits": 1000000, "currency": "USD"}}
                """)
            .exchange();
    assertThat(response).hasStatus2xxSuccessful();
  }

  private MvcTestResult submitInvoiceForAcme(String token, String invoiceNumber) {
    return mvc.post()
        .uri("/v1/invoices")
        .header("Authorization", "Bearer " + token)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {
              "supplier": {
                "name": "Globex",
                "taxId": "12-3456789",
                "buyerReference": "GLOBEX-01",
                "remittanceAddress": "https://globex.example/remittance-advice"
              },
              "invoiceNumber": "%s",
              "amount": {"minorUnits": 1240000, "currency": "USD"},
              "dueDate": "2026-11-30"
            }
            """
                .formatted(invoiceNumber))
        .exchange();
  }
}
