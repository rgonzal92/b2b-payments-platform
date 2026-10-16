package dev.rgonz.payments.platform.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.io.UnsupportedEncodingException;
import java.util.Currency;
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

/** Other modules place Holds through Holds; Issuer staff see them in Available Credit. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class HoldsTests {

  @Autowired MockMvcTester mvc;
  @Autowired Holds holds;

  @Test
  void placingTheSameHoldTwiceReservesTheCreditOnce() throws Exception {
    // The first placement takes all the credit, so a second Hold would be refused or go negative.
    var buyerId = buyerWithCreditLine(1_240_000);
    var holdId = UUID.randomUUID();
    var amount = new Money(1_240_000, Currency.getInstance("USD"));

    var first = holds.place(holdId, buyerId, amount);
    var second = holds.place(holdId, buyerId, amount);

    assertThat(first).isEqualTo(Holds.Outcome.PLACED);
    assertThat(second).isEqualTo(Holds.Outcome.PLACED);
    assertThat(availableCredit(buyerId)).isZero();
  }

  @Test
  void releasingAHoldTwiceReturnsItsCreditOnceAndLeavesTheBuyersOtherHolds() throws Exception {
    var buyerId = buyerWithCreditLine(1_240_000);
    var holdId = UUID.randomUUID();
    holds.place(holdId, buyerId, new Money(1_000_000, Currency.getInstance("USD")));
    holds.place(UUID.randomUUID(), buyerId, new Money(240_000, Currency.getInstance("USD")));

    holds.release(holdId);
    holds.release(holdId);

    assertThat(availableCredit(buyerId)).isEqualTo(1_000_000);
  }

  private BuyerId buyerWithCreditLine(long creditLine) {
    var buyerId = UUID.randomUUID();
    var buyer =
        mvc.put()
            .uri("/v1/buyers/{buyerId}", buyerId)
            .with(issuerStaff())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name": "Acme", "approvalThreshold": {"minorUnits": 1000000, "currency": "USD"}}
                """)
            .exchange();
    assertThat(buyer).hasStatus(201);
    var credit =
        mvc.put()
            .uri("/v1/buyers/{buyerId}/credit-line", buyerId)
            .with(issuerStaff())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"minorUnits": %d, "currency": "USD"}
                """
                    .formatted(creditLine))
            .exchange();
    assertThat(credit).hasStatus(201);
    return new BuyerId(buyerId);
  }

  private long availableCredit(BuyerId buyerId) throws UnsupportedEncodingException {
    var response =
        mvc.get()
            .uri("/v1/buyers/{buyerId}/credit", buyerId.value())
            .with(issuerStaff())
            .exchange();
    assertThat(response).hasStatus(200);
    return JsonPath.<Number>read(
            response.getResponse().getContentAsString(), "$.availableCredit.minorUnits")
        .longValue();
  }

  private static JwtRequestPostProcessor issuerStaff() {
    return jwt().authorities(new SimpleGrantedAuthority("ROLE_issuer-admin"));
  }
}
