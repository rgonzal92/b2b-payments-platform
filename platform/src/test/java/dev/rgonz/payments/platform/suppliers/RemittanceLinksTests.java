package dev.rgonz.payments.platform.suppliers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.cards.Cards;
import dev.rgonz.payments.platform.cards.SingleUseCard;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * A Supplier opens the one-time link of a Remittance Advice over HTTP, with no sign-in. Each test
 * issues a card and delivers its advice to a stubbed remittance address, which hands over the link.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "payables.payment-worker.enabled=false")
@AutoConfigureMockMvc
@AutoConfigureMockRestServiceServer
class RemittanceLinksTests {

  private static final String GLOBEX = "https://globex.example/remittance-advice";
  private static final Money AMOUNT = new Money(1_240_000, Currency.getInstance("USD"));

  @Autowired MockMvcTester mvc;
  @Autowired MockRestServiceServer supplierAddress;
  @Autowired Suppliers suppliers;
  @Autowired Cards cards;
  @Autowired RemittanceAdvices remittanceAdvices;

  @Test
  void theFirstOpenShowsTheCardsDetailsAndKeepsThemOutOfCaches() throws Exception {
    var card = issuedCard();
    var link = deliveredLink(card, Instant.now());

    var opened = mvc.get().uri(link).exchange();

    assertThat(opened).hasStatusOk();
    assertThat(opened).headers().hasValue("Cache-Control", "no-store");
    assertThat(opened)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {"amount": {"minorUnits": 1240000, "currency": "USD"}, "invoiceNumber": "INV-7"}
            """);
    var body = opened.getResponse().getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.pan"))
        .matches("990000\\d{10}")
        .endsWith(card.lastFour());
    assertThat(JsonPath.<String>read(body, "$.securityCode")).matches("\\d{3}");
    assertThat(Instant.parse(JsonPath.read(body, "$.expiresAt"))).isEqualTo(card.expiresAt());
  }

  // A new Supplier for each card, so tests don't share one.
  private SingleUseCard issuedCard() {
    var buyerId = new BuyerId(UUID.randomUUID());
    var supplier =
        suppliers
            .relate(
                buyerId,
                new SupplierOnInvoice("TAX-" + UUID.randomUUID(), "Globex", GLOBEX, "GLOBEX-01"))
            .supplier();
    return cards.issueSingleUse(UUID.randomUUID(), buyerId, supplier.id(), AMOUNT, Instant.now());
  }

  // Delivers the card's Remittance Advice as of now and returns the link the Supplier received.
  private String deliveredLink(SingleUseCard card, Instant now) {
    var link = new AtomicReference<String>();
    supplierAddress
        .expect(requestTo(GLOBEX))
        .andExpect(
            request ->
                link.set(
                    JsonPath.read(
                        ((MockClientHttpRequest) request).getBodyAsString(), "$.cardLink")))
        .andRespond(withSuccess());
    remittanceAdvices.deliver(
        new RemittanceAdvice(
            card.paymentId(),
            UUID.randomUUID(),
            "INV-7",
            card.supplierId(),
            card.amount(),
            card.cardToken()),
        now);
    return link.get();
  }
}
