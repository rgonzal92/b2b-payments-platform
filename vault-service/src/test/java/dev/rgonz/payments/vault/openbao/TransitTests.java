package dev.rgonz.payments.vault.openbao;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * An OpenBao that can't serve is {@link OpenBaoUnavailableException}, which IssueCard answers as
 * UNAVAILABLE so the caller waits and retries (design Q23); IssueCardTests covers a working one.
 */
class TransitTests {

  // TEST-ONLY token, not a secret: nothing answers it.
  private static final String TOKEN = "test-openbao-token";

  @Test
  void anOpenBaoThatDoesNotAnswerIsUnavailable() {
    // Port 1 on this machine: nothing listens there, so the connection is refused at once.
    var transit = new Transit(RestClient.builder(), "http://127.0.0.1:1", TOKEN);

    assertThatExceptionOfType(OpenBaoUnavailableException.class).isThrownBy(transit::newDataKey);
  }

  @Test
  void aSealedOpenBaoIsUnavailable() {
    var builder = RestClient.builder();
    var openBao = MockRestServiceServer.bindTo(builder).build();
    openBao
        .expect(requestTo("http://openbao/v1/transit/decrypt/card-kek"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    var transit = new Transit(builder, "http://openbao", TOKEN);

    assertThatExceptionOfType(OpenBaoUnavailableException.class)
        .isThrownBy(() -> transit.unwrap("vault:v1:test"));
  }

  @Test
  void aMissingTokenStopsTheService() {
    assertThatThrownBy(() -> new Transit(RestClient.builder(), "http://openbao", ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OPENBAO_TOKEN");
  }
}
