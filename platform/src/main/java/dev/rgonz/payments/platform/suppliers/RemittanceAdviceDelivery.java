package dev.rgonz.payments.platform.suppliers;

import dev.rgonz.payments.contracts.server.RemittanceLinksApi;
import dev.rgonz.payments.contracts.server.model.MoneyDto;
import dev.rgonz.payments.contracts.server.model.RemittanceAdviceDto;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Sends Remittance Advice to Suppliers over HTTP. The connect and read timeouts are short ({@code
 * spring.http.clients.*}), since the Payment saga's worker waits for the answer.
 */
@Component
class RemittanceAdviceDelivery implements RemittanceAdvices {

  private static final Duration LINK_VALIDITY = Duration.ofDays(7);

  private final Suppliers suppliers;
  private final RemittanceAdviceLinks links;
  private final RestClient http;
  private final String publicUrl;

  RemittanceAdviceDelivery(
      Suppliers suppliers,
      RemittanceAdviceLinks links,
      RestClient.Builder http,
      @Value("${platform.public-url}") String publicUrl) {
    this.suppliers = suppliers;
    this.links = links;
    this.http = http.build();
    this.publicUrl = publicUrl;
  }

  @Override
  public void deliver(RemittanceAdvice advice, Instant now) {
    var address = suppliers.remittanceAddressOf(advice.supplierId());
    var link = links.create(advice, now.plus(LINK_VALIDITY));
    var cardLink =
        UriComponentsBuilder.fromUriString(publicUrl)
            .path(RemittanceLinksApi.PATH_OPEN_REMITTANCE_LINK)
            .buildAndExpand(link.token())
            .toUri();
    // A URI, not a template: braces in a Supplier's address must not be expanded.
    http.post()
        .uri(URI.create(address))
        .header("Idempotency-Key", advice.paymentId().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new RemittanceAdviceDto()
                .id(advice.paymentId())
                .invoiceId(advice.invoiceId())
                .invoiceNumber(advice.invoiceNumber())
                .amount(
                    new MoneyDto()
                        .minorUnits(advice.amount().minorUnits())
                        .currency(
                            MoneyDto.CurrencyEnum.fromValue(
                                advice.amount().currency().getCurrencyCode())))
                .cardLink(cardLink)
                .cardLinkExpiresAt(link.expiresAt().atOffset(ZoneOffset.UTC)))
        .retrieve()
        // Anything but 2xx fails the attempt. The error names only the status: the Supplier's
        // answer
        // stays out of it, and so out of the logs, since it might echo the link back.
        .onStatus(
            status -> !status.is2xxSuccessful(),
            (request, response) -> {
              throw new IllegalStateException(
                  "The Supplier's remittance address answered "
                      + response.getStatusCode().value()
                      + ".");
            })
        .toBodilessEntity();
  }
}
