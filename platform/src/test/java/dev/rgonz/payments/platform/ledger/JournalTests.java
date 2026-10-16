package dev.rgonz.payments.platform.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The double-entry journal (ADR-0015) on PostgreSQL: a Journal Entry is posted once per idempotency
 * key, and never changed or deleted. Issuer staff see a Buyer Receivable's balance in Available
 * Credit. That every entry balances is {@link JournalEntryTests}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class JournalTests {

  private static final Currency USD = Currency.getInstance("USD");

  @Autowired MockMvcTester mvc;
  @Autowired Journal journal;
  @Autowired JdbcClient jdbc;

  @Test
  void postingTheSameIdempotencyKeyTwicePostsOnceAndReturnsTheFirstEntry() throws Exception {
    var buyerId = buyerWithCreditLine(10_000_000);
    var networkRecordId = UUID.randomUUID().toString();

    var first = journal.postClearing(networkRecordId, buyerId, usd(1_240_000));
    var second = journal.postClearing(networkRecordId, buyerId, usd(1_240_000));

    assertThat(second).isEqualTo(first);
    // The Clearing's debit to the Buyer Receivable counts against Available Credit once.
    assertThat(availableCredit(buyerId)).isEqualTo(10_000_000 - 1_240_000);
  }

  @Test
  void aPostedEntryIsNeverChangedOrDeleted() {
    var entryId =
        journal.postClearing(
            UUID.randomUUID().toString(), new BuyerId(UUID.randomUUID()), usd(1_240_000));

    for (var change :
        List.of(
            "update ledger.journal_line set amount_minor = 1 where entry_id = :id",
            "delete from ledger.journal_line where entry_id = :id",
            "update ledger.journal_entry set posted_at = now() where id = :id",
            "delete from ledger.journal_entry where id = :id")) {
      assertThatExceptionOfType(DataAccessException.class)
          .as(change)
          .isThrownBy(() -> jdbc.sql(change).param("id", entryId).update())
          .withMessageContaining("never changed or deleted");
    }
  }

  private static Money usd(long minorUnits) {
    return new Money(minorUnits, USD);
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
