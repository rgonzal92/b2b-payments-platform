package dev.rgonz.payments.platform.payables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.payments.platform.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Invoice intake over HTTP, with mocked tokens for two different Buyers. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InvoicesApiTests {

  @Autowired MockMvcTester mvc;

  @Test
  void aBuyersErpSubmitsAnInvoice() {
    var acme = newBuyer();
    var taxId = newTaxId();

    var response = submit(acme, newKey(), invoiceJson(taxId, "INV-1", 1_240_000));

    assertThat(response).hasStatus(201);
    assertThat(response)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {
              "status": "RECEIVED",
              "supplier": {
                "name": "Globex",
                "taxId": "%s",
                "buyerReference": "GLOBEX-01",
                "remittanceAddress": "https://globex.example/remittance-advice"
              },
              "invoiceNumber": "INV-1",
              "amount": {"minorUnits": 1240000, "currency": "USD"},
              "dueDate": "2026-11-30"
            }
            """
                .formatted(taxId));
    assertThat(response).bodyJson().extractingPath("$.id").isNotNull();
    assertThat(response).bodyJson().extractingPath("$.supplierId").isNotNull();
  }

  @Test
  void resendingWithTheSameKeyReturnsTheOriginalResponse() throws Exception {
    var acme = newBuyer();
    var key = newKey();
    var body = invoiceJson(newTaxId(), "INV-1", 1_240_000);
    var original = submit(acme, key, body);

    var resent = submit(acme, key, body);

    assertThat(resent).hasStatus(201);
    assertThat(resent).bodyJson().isStrictlyEqualTo(original.getResponse().getContentAsString());
  }

  @Test
  void reusingAKeyForADifferentRequestIsRefused() {
    var acme = newBuyer();
    var key = newKey();
    var taxId = newTaxId();
    submit(acme, key, invoiceJson(taxId, "INV-1", 1_240_000));

    var response = submit(acme, key, invoiceJson(taxId, "INV-1", 999));

    assertThat(response).hasStatus(422);
    assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
  }

  @Test
  void anInvoiceWithoutAnIdempotencyKeyIsRefused() {
    var acme = newBuyer();

    var response =
        mvc.post()
            .uri("/v1/invoices")
            .with(erpOf(acme))
            .contentType(MediaType.APPLICATION_JSON)
            .content(invoiceJson(newTaxId(), "INV-1", 1_240_000))
            .exchange();

    assertThat(response).hasStatus(400);
    assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
  }

  @Test
  void theSameSupplierAndInvoiceNumberUnderANewKeyIsADuplicate() {
    var acme = newBuyer();
    var taxId = newTaxId();
    submit(acme, newKey(), invoiceJson(taxId, "INV-1", 1_240_000));

    var response = submit(acme, newKey(), invoiceJson(taxId, "INV-1", 1_240_000));

    assertThat(response).hasStatus(409);
    assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
  }

  @Test
  void aRefusedDuplicateKeepsNothingUnderItsKey() {
    var acme = newBuyer();
    var taxId = newTaxId();
    submit(acme, newKey(), invoiceJson(taxId, "INV-1", 1_240_000));
    var key = newKey();
    submit(acme, key, invoiceJson(taxId, "INV-1", 1_240_000));

    var resent = submit(acme, key, invoiceJson(taxId, "INV-1", 1_240_000));

    assertThat(resent).hasStatus(409);
    assertThat(resent).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
  }

  @Test
  void aSecondBuyerReusesTheSupplierThroughItsOwnSupplierRelationship() throws Exception {
    var acme = newBuyer();
    var initech = newBuyer();
    var taxId = newTaxId();
    var toAcme = submit(acme, newKey(), invoiceJson(taxId, "GLOBEX-01", "INV-1", 1_240_000));

    // Invoice numbers are scoped per Buyer: the same number to another Buyer is a new Invoice.
    var toInitech = submit(initech, newKey(), invoiceJson(taxId, "V-778", "INV-1", 500_000));

    assertThat(toInitech).hasStatus(201);
    assertThat(toInitech)
        .bodyJson()
        .extractingPath("$.supplierId")
        .isEqualTo(JsonPath.read(toAcme.getResponse().getContentAsString(), "$.supplierId"));
    assertThat(toInitech).bodyJson().extractingPath("$.supplier.buyerReference").isEqualTo("V-778");
  }

  @Test
  void anInvoiceCarryingTheSuppliersAcceptorIdStoresItOnTheSupplier() {
    var acme = newBuyer();
    var taxId = newTaxId();

    var carrying =
        submit(acme, newKey(), invoiceJson(taxId, "INV-1", 1_240_000, "GLOBEX000000001"));
    var later = submit(acme, newKey(), invoiceJson(taxId, "INV-2", 500_000));

    assertThat(carrying).hasStatus(201);
    assertThat(carrying)
        .bodyJson()
        .extractingPath("$.supplier.acceptorId")
        .isEqualTo("GLOBEX000000001");
    assertThat(later).hasStatus(201);
    assertThat(later)
        .bodyJson()
        .extractingPath("$.supplier.acceptorId")
        .isEqualTo("GLOBEX000000001");
  }

  // Like the remittance address, so a later Invoice can't redirect the Supplier's Charges.
  @Test
  void theFirstInvoiceCarryingAnAcceptorIdSetsItAndLaterOnesDontChangeIt() {
    var acme = newBuyer();
    var initech = newBuyer();
    var taxId = newTaxId();

    var without = submit(acme, newKey(), invoiceJson(taxId, "INV-1", 1_240_000));
    var first = submit(acme, newKey(), invoiceJson(taxId, "INV-2", 1_240_000, "GLOBEX000000001"));
    var different =
        submit(initech, newKey(), invoiceJson(taxId, "INV-3", 1_240_000, "OTHER0000000002"));

    assertThat(without).hasStatus(201);
    assertThat(without).bodyJson().extractingPath("$.supplier.acceptorId").isNull();
    assertThat(first)
        .bodyJson()
        .extractingPath("$.supplier.acceptorId")
        .isEqualTo("GLOBEX000000001");
    assertThat(different).hasStatus(201);
    assertThat(different)
        .bodyJson()
        .extractingPath("$.supplier.acceptorId")
        .isEqualTo("GLOBEX000000001");
  }

  @Test
  void idempotencyKeysAreKeptPerBuyer() throws Exception {
    var acme = newBuyer();
    var initech = newBuyer();
    var key = newKey();
    var body = invoiceJson(newTaxId(), "INV-1", 1_240_000);
    var acmes = submit(acme, key, body);

    var initechs = submit(initech, key, body);

    assertThat(initechs).hasStatus(201);
    assertThat(initechs)
        .bodyJson()
        .extractingPath("$.id")
        .isNotEqualTo(JsonPath.read(acmes.getResponse().getContentAsString(), "$.id"));
  }

  @Test
  void aBuyerGetsItsOwnInvoice() throws Exception {
    var acme = newBuyer();
    var submitted = submit(acme, newKey(), invoiceJson(newTaxId(), "INV-1", 1_240_000));
    var invoiceId = JsonPath.read(submitted.getResponse().getContentAsString(), "$.id");

    var response =
        mvc.get().uri("/v1/invoices/{invoiceId}", invoiceId).with(erpOf(acme)).exchange();

    assertThat(response).hasStatus(200);
    assertThat(response).bodyJson().isStrictlyEqualTo(submitted.getResponse().getContentAsString());
  }

  @Test
  void anotherBuyersInvoiceIsNotFound() throws Exception {
    var acme = newBuyer();
    var initech = newBuyer();
    var submitted = submit(acme, newKey(), invoiceJson(newTaxId(), "INV-1", 1_240_000));
    var invoiceId = JsonPath.read(submitted.getResponse().getContentAsString(), "$.id");

    var response =
        mvc.get().uri("/v1/invoices/{invoiceId}", invoiceId).with(erpOf(initech)).exchange();

    assertThat(response).hasStatus(404);
    assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
  }

  @Test
  void aTokenWithoutAKnownBuyerIsForbidden() {
    var body = invoiceJson(newTaxId(), "INV-1", 1_240_000);
    var unknownBuyer = tokenOf(UUID.randomUUID().toString(), ERP);
    var notAnId = tokenOf("acme", ERP);
    var noBuyer = jwt().authorities(new SimpleGrantedAuthority(ERP));

    for (var token : List.of(unknownBuyer, notAnId, noBuyer)) {
      var response =
          mvc.post()
              .uri("/v1/invoices")
              .with(token)
              .header("Idempotency-Key", newKey())
              .contentType(MediaType.APPLICATION_JSON)
              .content(body)
              .exchange();

      assertThat(response).hasStatus(403);
      assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }
  }

  @Test
  void onlyAnErpSubmitsInvoicesAndOnlyBuyerSideRolesReadThem() throws Exception {
    var acme = newBuyer();
    var submitted = submit(acme, newKey(), invoiceJson(newTaxId(), "INV-1", 1_240_000));
    var invoiceId = JsonPath.read(submitted.getResponse().getContentAsString(), "$.id");

    var approverSubmits =
        mvc.post()
            .uri("/v1/invoices")
            .with(tokenOf(acme.toString(), "ROLE_approver"))
            .header("Idempotency-Key", newKey())
            .contentType(MediaType.APPLICATION_JSON)
            .content(invoiceJson(newTaxId(), "INV-2", 1_240_000))
            .exchange();
    var approverReads =
        mvc.get()
            .uri("/v1/invoices/{invoiceId}", invoiceId)
            .with(tokenOf(acme.toString(), "ROLE_approver"))
            .exchange();
    var issuerStaffReads =
        mvc.get()
            .uri("/v1/invoices/{invoiceId}", invoiceId)
            .with(tokenOf(acme.toString(), "ROLE_issuer-admin"))
            .exchange();

    assertThat(approverSubmits).hasStatus(403);
    assertThat(approverReads).hasStatus(200);
    assertThat(issuerStaffReads).hasStatus(403);
  }

  @Test
  void invalidInvoicesAreRejectedAsProblems() {
    var acme = newBuyer();
    var taxId = newTaxId();
    var valid = invoiceJson(taxId, "INV-1", 1_240_000);
    var invalid =
        List.of(
            invoiceJson(taxId, "INV-1", 0),
            invoiceJson(taxId, "   ", 1_240_000),
            invoiceJson("TAX 1", "INV-1", 1_240_000),
            valid.replace("https://globex.example/remittance-advice", "globex.example"),
            valid.replace("\"USD\"", "\"EUR\""),
            valid.replace("\"dueDate\": \"2026-11-30\"", "\"dueDate\": \"30/11/2026\""),
            // ISO 8583's DE 42 holds 15 characters.
            invoiceJson(taxId, "INV-1", 1_240_000, "GLOBEX0000000001"),
            invoiceJson(taxId, "INV-1", 1_240_000, ""),
            invoiceJson(taxId, "INV-1", 1_240_000, "   "),
            """
            {"invoiceNumber": "INV-1", "amount": {"minorUnits": 100, "currency": "USD"}, "dueDate": "2026-11-30"}
            """);

    for (var body : invalid) {
      var response = submit(acme, newKey(), body);

      assertThat(response).as(body).hasStatus(400);
      assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }
  }

  private static final String ERP = "ROLE_erp";

  private UUID newBuyer() {
    var buyerId = UUID.randomUUID();
    var response =
        mvc.put()
            .uri("/v1/buyers/{buyerId}", buyerId)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_issuer-admin")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name": "Buyer %s", "approvalThreshold": {"minorUnits": 1000000, "currency": "USD"}}
                """
                    .formatted(buyerId))
            .exchange();
    assertThat(response).hasStatus(201);
    return buyerId;
  }

  private MvcTestResult submit(UUID buyerId, String idempotencyKey, String body) {
    return mvc.post()
        .uri("/v1/invoices")
        .with(erpOf(buyerId))
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  private static JwtRequestPostProcessor erpOf(UUID buyerId) {
    return tokenOf(buyerId.toString(), ERP);
  }

  private static JwtRequestPostProcessor tokenOf(String buyerId, String authority) {
    return jwt()
        .jwt(token -> token.claim("buyer_id", buyerId))
        .authorities(new SimpleGrantedAuthority(authority));
  }

  private static String newKey() {
    return UUID.randomUUID().toString();
  }

  // Each test names its own Supplier, so tests don't see each other's Suppliers.
  private static String newTaxId() {
    return "TAX-" + UUID.randomUUID();
  }

  private static String invoiceJson(String taxId, String invoiceNumber, long minorUnits) {
    return invoiceJson(taxId, "GLOBEX-01", invoiceNumber, minorUnits);
  }

  private static String invoiceJson(
      String taxId, String buyerReference, String invoiceNumber, long minorUnits) {
    return """
        {
          "supplier": {
            "name": "Globex",
            "taxId": "%s",
            "buyerReference": "%s",
            "remittanceAddress": "https://globex.example/remittance-advice"
          },
          "invoiceNumber": "%s",
          "amount": {"minorUnits": %d, "currency": "USD"},
          "dueDate": "2026-11-30"
        }
        """
        .formatted(taxId, buyerReference, invoiceNumber, minorUnits);
  }

  private static String invoiceJson(
      String taxId, String invoiceNumber, long minorUnits, String acceptorId) {
    return invoiceJson(taxId, invoiceNumber, minorUnits)
        .replace(
            "\"remittanceAddress\":",
            "\"acceptorId\": \"%s\",\n    \"remittanceAddress\":".formatted(acceptorId));
  }
}
