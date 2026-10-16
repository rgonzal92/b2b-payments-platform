package dev.rgonz.payments.platform.payables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.payments.platform.TestcontainersConfiguration;
import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.io.UnsupportedEncodingException;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.modulith.test.PublishedEventsExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Approvers approve and reject Invoices over HTTP, with mocked tokens, and the final approval
 * publishes InvoiceApproved.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(PublishedEventsExtension.class)
class ApprovalsApiTests {

  private static final long THRESHOLD = 1_000_000;

  @Autowired MockMvcTester mvc;
  @Autowired CompletedEventPublications completedEvents;

  @Test
  void oneApprovalCompletesAnInvoiceAtTheApprovalThreshold() throws Exception {
    var acme = newBuyer();
    var invoiceId = submitInvoice(acme, THRESHOLD);

    var response = decide(approverOf(acme, "ann"), invoiceId, "approval");

    assertThat(response).hasStatus(200);
    assertThat(response).bodyJson().extractingPath("$.status").isEqualTo("APPROVED");
  }

  @Test
  void anInvoiceAboveTheApprovalThresholdNeedsTwoDifferentApprovers() throws Exception {
    var acme = newBuyer();
    var invoiceId = submitInvoice(acme, THRESHOLD + 1);

    var first = decide(approverOf(acme, "ann"), invoiceId, "approval");
    var second = decide(approverOf(acme, "bob"), invoiceId, "approval");

    assertThat(first).hasStatus(200);
    assertThat(first).bodyJson().extractingPath("$.status").isEqualTo("RECEIVED");
    assertThat(second).hasStatus(200);
    assertThat(second).bodyJson().extractingPath("$.status").isEqualTo("APPROVED");
  }

  @Test
  void theSameApproverApprovingTwiceCountsOnce() throws Exception {
    var acme = newBuyer();
    var invoiceId = submitInvoice(acme, THRESHOLD + 1);
    decide(approverOf(acme, "ann"), invoiceId, "approval");

    var again = decide(approverOf(acme, "ann"), invoiceId, "approval");

    assertThat(again).hasStatus(200);
    assertThat(again).bodyJson().extractingPath("$.status").isEqualTo("RECEIVED");
  }

  @Test
  void aRejectionIsFinal() throws Exception {
    var acme = newBuyer();
    var invoiceId = submitInvoice(acme, THRESHOLD + 1);
    decide(approverOf(acme, "ann"), invoiceId, "approval");

    // Still awaiting a second approval, so the Approver who approved may reject it.
    var rejected = decide(approverOf(acme, "ann"), invoiceId, "rejection");
    var laterApproval = decide(approverOf(acme, "bob"), invoiceId, "approval");
    var laterRejection = decide(approverOf(acme, "bob"), invoiceId, "rejection");
    var sameRejectionAgain = decide(approverOf(acme, "ann"), invoiceId, "rejection");

    assertThat(rejected).hasStatus(200);
    assertThat(rejected).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
    for (var refused : List.of(laterApproval, laterRejection, sameRejectionAgain)) {
      assertThat(refused).hasStatus(409);
      assertThat(refused).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }
  }

  @Test
  void anApprovedInvoiceTakesNoNewDecisions() throws Exception {
    var acme = newBuyer();
    var invoiceId = submitInvoice(acme, THRESHOLD);
    decide(approverOf(acme, "ann"), invoiceId, "approval");

    var anotherApproval = decide(approverOf(acme, "bob"), invoiceId, "approval");
    var rejection = decide(approverOf(acme, "bob"), invoiceId, "rejection");
    var sameApprovalAgain = decide(approverOf(acme, "ann"), invoiceId, "approval");

    for (var refused : List.of(anotherApproval, rejection)) {
      assertThat(refused).hasStatus(409);
      assertThat(refused).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }
    assertThat(sameApprovalAgain).hasStatus(200);
    assertThat(sameApprovalAgain).bodyJson().extractingPath("$.status").isEqualTo("APPROVED");
  }

  @Test
  void eachDecisionIsShownOnTheInvoiceWithItsApproverAndTime() throws Exception {
    var acme = newBuyer();
    var invoiceId = submitInvoice(acme, THRESHOLD + 1);
    decide(approverOf(acme, "ann"), invoiceId, "approval");
    decide(approverOf(acme, "ann"), invoiceId, "approval");
    decide(approverOf(acme, "bob"), invoiceId, "rejection");

    var response =
        mvc.get()
            .uri("/v1/invoices/{invoiceId}", invoiceId)
            .with(tokenOf(acme, "ROLE_erp", "acme-erp"))
            .exchange();

    assertThat(response).hasStatus(200);
    assertThat(response)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {
              "status": "REJECTED",
              "decisions": [
                {"decision": "APPROVED", "approverId": "user-ann", "approverName": "ann"},
                {"decision": "REJECTED", "approverId": "user-bob", "approverName": "bob"}
              ]
            }
            """);
    assertThat(response).bodyJson().extractingPath("$.decisions[0].decision").isEqualTo("APPROVED");
    assertThat(response).bodyJson().extractingPath("$.decisions[0].decidedAt").isNotNull();
    assertThat(response).bodyJson().extractingPath("$.decisions[1].decidedAt").isNotNull();
  }

  @Test
  void anotherBuyersApproverGetsNotFound() throws Exception {
    var acme = newBuyer();
    var initech = newBuyer();
    var invoiceId = submitInvoice(acme, THRESHOLD);

    for (var decision : List.of("approval", "rejection")) {
      var response = decide(approverOf(initech, "ivan"), invoiceId, decision);

      assertThat(response).hasStatus(404);
      assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }
  }

  @Test
  void onlyApproversDecide() throws Exception {
    var acme = newBuyer();
    var invoiceId = submitInvoice(acme, THRESHOLD);

    for (var role : List.of("ROLE_erp", "ROLE_issuer-admin")) {
      for (var decision : List.of("approval", "rejection")) {
        var response = decide(tokenOf(acme, role, "someone"), invoiceId, decision);

        assertThat(response).as(role + " " + decision).hasStatus(403);
        assertThat(response).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
      }
    }
  }

  @Test
  void onlyTheFinalApprovalPublishesInvoiceApproved(PublishedEvents events) throws Exception {
    var acme = newBuyer();
    var invoice = submitInvoiceJson(acme, THRESHOLD + 1);
    String invoiceId = JsonPath.read(invoice, "$.id");
    String supplierId = JsonPath.read(invoice, "$.supplierId");

    decide(approverOf(acme, "ann"), invoiceId, "approval");
    assertThat(events.ofType(InvoiceApproved.class)).isEmpty();
    decide(approverOf(acme, "bob"), invoiceId, "approval");
    decide(approverOf(acme, "bob"), invoiceId, "approval");

    assertThat(events.ofType(InvoiceApproved.class))
        .containsExactly(
            new InvoiceApproved(
                UUID.fromString(invoiceId),
                new BuyerId(acme),
                UUID.fromString(supplierId),
                new Money(THRESHOLD + 1, Currency.getInstance("USD"))));
  }

  @Test
  void aRejectionPublishesNoInvoiceApproved(PublishedEvents events) throws Exception {
    var acme = newBuyer();
    var invoiceId = submitInvoice(acme, THRESHOLD);

    decide(approverOf(acme, "ann"), invoiceId, "rejection");

    assertThat(events.ofType(InvoiceApproved.class)).isEmpty();
  }

  @Test
  void invoiceApprovedGoesThroughTheEventPublicationRegistry() throws Exception {
    var acme = newBuyer();
    var invoiceId = UUID.fromString(submitInvoice(acme, THRESHOLD));

    decide(approverOf(acme, "ann"), invoiceId.toString(), "approval");

    assertThat(completedEvents.findAll())
        .extracting(EventPublication::getEvent)
        .filteredOn(
            event ->
                event instanceof InvoiceApproved approved && approved.invoiceId().equals(invoiceId))
        .hasSize(1);
  }

  // Stands in for the Payment saga (#17): the registry stores a publication only for an event
  // that a transactional listener receives.
  @TestConfiguration(proxyBeanMethods = false)
  static class PaymentSagaStandIn {

    @TransactionalEventListener
    void on(InvoiceApproved event) {
      // Receiving the event is enough: the registry records its publication as completed.
    }
  }

  private UUID newBuyer() {
    var buyerId = UUID.randomUUID();
    var response =
        mvc.put()
            .uri("/v1/buyers/{buyerId}", buyerId)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_issuer-admin")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name": "Buyer %s", "approvalThreshold": {"minorUnits": %d, "currency": "USD"}}
                """
                    .formatted(buyerId, THRESHOLD))
            .exchange();
    assertThat(response).hasStatus(201);
    return buyerId;
  }

  private String submitInvoice(UUID buyerId, long minorUnits) throws UnsupportedEncodingException {
    return JsonPath.read(submitInvoiceJson(buyerId, minorUnits), "$.id");
  }

  private String submitInvoiceJson(UUID buyerId, long minorUnits)
      throws UnsupportedEncodingException {
    var response =
        mvc.post()
            .uri("/v1/invoices")
            .with(tokenOf(buyerId, "ROLE_erp", "acme-erp"))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "supplier": {
                    "name": "Globex",
                    "taxId": "TAX-%s",
                    "buyerReference": "GLOBEX-01",
                    "remittanceAddress": "https://globex.example/remittance-advice"
                  },
                  "invoiceNumber": "INV-1",
                  "amount": {"minorUnits": %d, "currency": "USD"},
                  "dueDate": "2026-11-30"
                }
                """
                    .formatted(UUID.randomUUID(), minorUnits))
            .exchange();
    assertThat(response).hasStatus(201);
    return response.getResponse().getContentAsString();
  }

  private MvcTestResult decide(JwtRequestPostProcessor token, String invoiceId, String decision) {
    return mvc.post().uri("/v1/invoices/{invoiceId}/" + decision, invoiceId).with(token).exchange();
  }

  private static JwtRequestPostProcessor approverOf(UUID buyerId, String name) {
    return tokenOf(buyerId, "ROLE_approver", name);
  }

  // The subject identifies the Approver; preferred_username is the name kept with each decision.
  private static JwtRequestPostProcessor tokenOf(UUID buyerId, String authority, String name) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject("user-" + name)
                    .claim("preferred_username", name)
                    .claim("buyer_id", buyerId.toString()))
        .authorities(new SimpleGrantedAuthority(authority));
  }
}
