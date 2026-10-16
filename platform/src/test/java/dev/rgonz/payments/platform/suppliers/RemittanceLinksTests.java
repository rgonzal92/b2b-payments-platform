package dev.rgonz.payments.platform.suppliers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.cards.CardExpiryJob;
import dev.rgonz.payments.platform.cards.Cards;
import dev.rgonz.payments.platform.cards.SingleUseCard;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
  @Autowired JdbcClient jdbc;
  @Autowired ObservationRegistry observations;
  @Autowired ApplicationContext context;
  @MockitoSpyBean RemittanceAdviceLinks links;

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

  @Test
  void theLinksTokenStaysOutOfTraces() {
    var link = deliveredLink(issuedCard(), Instant.now());
    var token = link.substring(link.lastIndexOf('/') + 1);
    var recorded = new CopyOnWriteArrayList<String>();
    // Every observation's name and tags, which become spans and metrics.
    observations
        .observationConfig()
        .observationHandler(
            new ObservationHandler<>() {
              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              @Override
              public void onStop(Observation.Context context) {
                recorded.add(context.getName() + " " + context.getAllKeyValues());
              }
            });

    assertThat(mvc.get().uri(link).exchange()).hasStatusOk();

    assertThat(recorded)
        .anyMatch(observation -> observation.startsWith("http.server.requests"))
        .noneMatch(observation -> observation.contains(token));
  }

  @Test
  void aSecondOpenIsGone() {
    var link = deliveredLink(issuedCard(), Instant.now());
    assertThat(mvc.get().uri(link).exchange()).hasStatusOk();

    var second = mvc.get().uri(link).exchange();

    assertThat(second).hasStatus(410);
    assertThat(second).bodyJson().extractingPath("$.status").isEqualTo(410);
    assertThat(second).bodyText().doesNotContainPattern("990000\\d{10}");
  }

  @Test
  void aTokenThatMatchesNoLinkIsNotFound() {
    var unknown = mvc.get().uri("/v1/remittance-links/{token}", "A".repeat(43)).exchange();

    assertThat(unknown).hasStatus(404);
    assertThat(unknown).bodyJson().extractingPath("$.status").isEqualTo(404);
  }

  @Test
  void aLinkOpensForSevenDaysAfterItsAdviceAndIsGoneAfterThat() {
    var sevenDaysAgo = Instant.now().minus(Duration.ofDays(7));
    var stillValid = deliveredLink(issuedCard(), sevenDaysAgo.plus(Duration.ofMinutes(1)));
    var expired = deliveredLink(issuedCard(), sevenDaysAgo.minus(Duration.ofMinutes(1)));

    assertThat(mvc.get().uri(stillValid).exchange()).hasStatusOk();
    assertThat(mvc.get().uri(expired).exchange()).hasStatus(410);
  }

  @Test
  void theLinkOfACancelledCardIsGone() {
    var card = issuedCard();
    var link = deliveredLink(card, Instant.now());

    cards.cancel(card.cardToken());

    assertThat(mvc.get().uri(link).exchange()).hasStatus(410);
    // Nothing was shown, so the link isn't marked used: the stuck-Payment runbook reads a used link
    // as a Supplier holding the card's details.
    assertThat(
            jdbc.sql("select link_used_at is null from suppliers.remittance_advice where id = :id")
                .param("id", card.paymentId())
                .query(Boolean.class)
                .single())
        .isTrue();
  }

  @Test
  void theLinkOfAnExpiredCardIsGone() {
    // Issued in 2020, so running the expiry job at its expiry expires no other test's card.
    var card = issuedCard(Instant.parse("2020-01-01T00:00:00Z"));
    var link = deliveredLink(card, Instant.now());

    CardExpiryJob.runAt(context, card.expiresAt());

    assertThat(mvc.get().uri(link).exchange()).hasStatus(410);
  }

  @Test
  void theUnusedLinkOfAnAuthorizedCardStillOpens() {
    var card = issuedCard();
    var link = deliveredLink(card, Instant.now());

    cards.authorize(card.cardToken(), Instant.now());

    assertThat(mvc.get().uri(link).exchange()).hasStatusOk();
  }

  @Test
  void ofTwoOpensAtTheSameTimeExactlyOneShowsTheCard() throws Exception {
    var link = deliveredLink(issuedCard(), Instant.now());
    // Each open waits until the other arrives, so both reach the link at once.
    var bothArrived = new CyclicBarrier(2);
    doAnswer(
            invocation -> {
              bothArrived.await(10, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .when(links)
        .open(any());

    var statuses = new ArrayList<Integer>();
    try (var opener = Executors.newFixedThreadPool(2)) {
      var opens =
          List.of(
              opener.submit(() -> mvc.get().uri(link).exchange().getResponse().getStatus()),
              opener.submit(() -> mvc.get().uri(link).exchange().getResponse().getStatus()));
      for (var open : opens) {
        statuses.add(open.get(30, TimeUnit.SECONDS));
      }
    }

    assertThat(statuses).containsExactlyInAnyOrder(200, 410);
  }

  private SingleUseCard issuedCard() {
    return issuedCard(Instant.now());
  }

  // A new Supplier for each card, so tests don't share one.
  private SingleUseCard issuedCard(Instant now) {
    var buyerId = new BuyerId(UUID.randomUUID());
    var supplier =
        suppliers
            .relate(
                buyerId,
                new SupplierOnInvoice(
                    "TAX-" + UUID.randomUUID(), "Globex", GLOBEX, "GLOBEX-01", null))
            .supplier();
    return cards.issueSingleUse(UUID.randomUUID(), buyerId, supplier.id(), AMOUNT, now);
  }

  // Delivers the card's Remittance Advice as of now and returns the link the Supplier received.
  private String deliveredLink(SingleUseCard card, Instant now) {
    var link = new AtomicReference<String>();
    // The stub takes no new expectations once it has served a request, so each delivery starts it
    // afresh.
    supplierAddress.reset();
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
