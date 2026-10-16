package dev.rgonz.payments.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Globex, the simulator's Supplier, receives Remittance Advice from the platform and opens each
 * card link once, against a stub platform that serves the link.
 */
@SpringBootTest(properties = "simulator.seed.enabled=false")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class GlobexSupplierTests {

  private static final String TOKEN = "dGVzdC1vbmx5LWxpbmstdG9rZW4tbm90LWEtc2VjcmV0";
  private static final String PAN = "9900001234567899";

  static final AtomicInteger linkOpens = new AtomicInteger();
  static final HttpServer platform = startStubPlatform();

  @AfterAll
  static void stopStub() {
    platform.stop(0);
  }

  @Autowired MockMvcTester mvc;
  @Autowired GlobexSupplier globex;

  @Test
  void opensEachAdvicesCardLinkOnceAndLogsOnlyTheLastFourDigits(CapturedOutput output) {
    var invoiceId = UUID.randomUUID();
    var adviceId = UUID.randomUUID().toString();
    var advice =
        """
        {
          "id": "%s",
          "invoiceId": "%s",
          "invoiceNumber": "INV-42",
          "amount": {"minorUnits": 1240000, "currency": "USD"},
          "cardLink": "http://localhost:%d/v1/remittance-links/%s",
          "cardLinkExpiresAt": "2026-10-23T12:00:00Z"
        }
        """
            .formatted(adviceId, invoiceId, platform.getAddress().getPort(), TOKEN);

    // The platform delivers at least once: the same advice again, under the same key.
    var first = deliver(adviceId, advice);
    var repeat = deliver(adviceId, advice);

    assertThat(first).hasStatus2xxSuccessful();
    assertThat(repeat).hasStatus2xxSuccessful();
    assertThat(linkOpens).hasValue(1);
    assertThat(globex.cardFor(invoiceId))
        .hasValueSatisfying(
            card -> {
              assertThat(card.invoiceNumber()).isEqualTo("INV-42");
              assertThat(card.lastFour()).isEqualTo("7899");
            });
    assertThat(output).contains("7899").doesNotContain(PAN).doesNotContain(TOKEN);
  }

  // A server-to-server POST, with no session cookie or CSRF token, as the platform sends it.
  private MvcTestResult deliver(String idempotencyKey, String advice) {
    return mvc.post()
        .uri("/globex/remittance-advice")
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(advice)
        .exchange();
  }

  // Serves the card link's details the first time, as the platform does, and counts every open.
  private static HttpServer startStubPlatform() {
    try {
      var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext(
          "/v1/remittance-links/" + TOKEN,
          exchange -> {
            linkOpens.incrementAndGet();
            var bytes =
                """
                {
                  "pan": "%s",
                  "securityCode": "321",
                  "expiresAt": "2026-11-15T12:00:00Z",
                  "amount": {"minorUnits": 1240000, "currency": "USD"},
                  "invoiceNumber": "INV-42"
                }
                """
                    .formatted(PAN)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
