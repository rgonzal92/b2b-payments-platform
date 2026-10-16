package dev.rgonz.payments.platform.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CreditApiTests {

  @Autowired MockMvcTester mvc;

  @Test
  void issuerStaffSetABuyersCreditLine() {
    var buyerId = existingBuyer();

    var response = putCreditLine(buyerId, ISSUER_ADMIN, usd(10_000_000));

    assertThat(response).hasStatus(201);
    assertThat(response)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"minorUnits": 10000000, "currency": "USD"}
            """);
  }

  @Test
  void settingItAgainReplacesTheCreditLine() {
    var buyerId = existingBuyer();
    putCreditLine(buyerId, ISSUER_ADMIN, usd(10_000_000));

    var response = putCreditLine(buyerId, ISSUER_ADMIN, usd(2_500_000));

    assertThat(response).hasStatus(200);
    assertThat(getCredit(buyerId, ISSUER_ADMIN))
        .bodyJson()
        .extractingPath("$.creditLine.minorUnits")
        .isEqualTo(2_500_000);
  }

  @Test
  void withNoHoldsAvailableCreditEqualsTheCreditLine() {
    var buyerId = existingBuyer();
    putCreditLine(buyerId, ISSUER_ADMIN, usd(10_000_000));

    var response = getCredit(buyerId, ISSUER_ADMIN);

    assertThat(response).hasStatus(200);
    assertThat(response)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"creditLine": {"minorUnits": 10000000, "currency": "USD"},
             "availableCredit": {"minorUnits": 10000000, "currency": "USD"}}
            """);
  }

  @Test
  void anUnknownBuyerGetsNoCreditLine() {
    var response = putCreditLine(UUID.randomUUID(), ISSUER_ADMIN, usd(10_000_000));

    assertThat(response).hasStatus(404);
    assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
  }

  @Test
  void aBuyerWithoutACreditLineHasNoCreditToRead() {
    var neverSet = getCredit(existingBuyer(), ISSUER_ADMIN);
    var unknownBuyer = getCredit(UUID.randomUUID(), ISSUER_ADMIN);

    for (var response : List.of(neverSet, unknownBuyer)) {
      assertThat(response).hasStatus(404);
      assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }
  }

  @Test
  void invalidCreditLinesAreRejectedAsProblems() {
    var buyerId = existingBuyer();

    var negative = putCreditLine(buyerId, ISSUER_ADMIN, usd(-1));
    var otherCurrency =
        putCreditLine(
            buyerId,
            ISSUER_ADMIN,
            """
            {"minorUnits": 100, "currency": "EUR"}
            """);
    var noCurrency =
        putCreditLine(
            buyerId,
            ISSUER_ADMIN,
            """
        {"minorUnits": 100}
        """);

    for (var response : List.of(negative, otherCurrency, noCurrency)) {
      assertThat(response).hasStatus(400);
      assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }
  }

  @Test
  void onlyIssuerStaffMaySetOrReadCredit() {
    var buyerId = existingBuyer();
    putCreditLine(buyerId, ISSUER_ADMIN, usd(10_000_000));

    var set = putCreditLine(buyerId, "ROLE_erp", usd(99_000_000));
    var read = getCredit(buyerId, "ROLE_approver");

    for (var response : List.of(set, read)) {
      assertThat(response).hasStatus(403);
      assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }
  }

  private static final String ISSUER_ADMIN = "ROLE_issuer-admin";

  private UUID existingBuyer() {
    var buyerId = UUID.randomUUID();
    var response =
        mvc.put()
            .uri("/v1/buyers/{buyerId}", buyerId)
            .with(jwt().authorities(new SimpleGrantedAuthority(ISSUER_ADMIN)))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name": "Acme", "approvalThreshold": {"minorUnits": 1000000, "currency": "USD"}}
                """)
            .exchange();
    assertThat(response).hasStatus(201);
    return buyerId;
  }

  private MvcTestResult putCreditLine(UUID buyerId, String authority, String body) {
    return mvc.put()
        .uri("/v1/buyers/{buyerId}/credit-line", buyerId)
        .with(jwt().authorities(new SimpleGrantedAuthority(authority)))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  private MvcTestResult getCredit(UUID buyerId, String authority) {
    return mvc.get()
        .uri("/v1/buyers/{buyerId}/credit", buyerId)
        .with(jwt().authorities(new SimpleGrantedAuthority(authority)))
        .exchange();
  }

  private static String usd(long minorUnits) {
    return """
        {"minorUnits": %d, "currency": "USD"}
        """
        .formatted(minorUnits);
  }
}
