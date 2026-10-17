package dev.rgonz.payments.simulator;

import dev.rgonz.payments.contracts.client.model.Money;
import dev.rgonz.payments.contracts.client.model.Payment;
import dev.rgonz.payments.simulator.GlobexSupplier.ReceivedCard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Makes the load test's cards through the platform's real path: Initech's ERP sends N $10.00
 * Invoices to Globex, Initech's Approver approves each, the run waits for each Payment, and Globex
 * reads each card through its one-time link. Then it writes every card Globex received to the cards
 * file, overwriting the last run's. The file holds PANs and security codes; the logs and the answer
 * never do.
 */
@RestController
class LoadTestCards {

  static final String DEMO_PATH = "/demo/load-test-cards";
  static final int MAX_COUNT = 10_000;

  /** The cards file's first line: the columns the load test reads. */
  static final String HEADER = "pan,securityCode,expiry,amountMinorUnits,currency,acceptorId";

  private static final Logger log = LoggerFactory.getLogger(LoadTestCards.class);
  // $10.00.
  private static final long AMOUNT = 1_000;
  // Invoices in flight at once. The platform's saga runs up to 10 steps a second and a Payment
  // takes
  // three, so a few dozen keep it busy; more would only wait in its queue.
  private static final int IN_FLIGHT = 32;
  private static final Duration PAYMENT_WAIT = Duration.ofMinutes(2);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
  private static final int PROGRESS_EVERY = 100;

  private final Initech initech;
  private final GlobexSupplier globex;
  private final Path cardsFile;
  private final String globexAcceptorId;

  LoadTestCards(
      Initech initech,
      GlobexSupplier globex,
      @Value("${simulator.load-test.cards-file}") String cardsFile,
      @Value("${simulator.globex.acceptor-id}") String globexAcceptorId) {
    this.initech = initech;
    this.globex = globex;
    this.cardsFile = Path.of(cardsFile).toAbsolutePath().normalize();
    this.globexAcceptorId = globexAcceptorId;
  }

  /**
   * Sends {@code count} (1 to 10,000) Invoices and writes the cards Globex received. A card whose
   * Payment failed, or didn't complete within two minutes, is left out, so {@code written} can be
   * less than {@code requested}.
   */
  @PostMapping(DEMO_PATH)
  Result run(@RequestParam int count) throws IOException {
    if (count < 1 || count > MAX_COUNT) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "count must be from 1 to " + MAX_COUNT);
    }
    // Invoice numbers are unique per Buyer and Supplier: each run gets its own prefix.
    var run = "LT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    var started = Instant.now();
    var settled = new AtomicInteger();
    log.info("Load-test run {} sends {} Invoices from Initech to Globex", run, count);
    var futures = new ArrayList<Future<Optional<ReceivedCard>>>(count);
    // Closing the executor waits for every Invoice.
    try (var executor = Executors.newFixedThreadPool(IN_FLIGHT)) {
      IntStream.rangeClosed(1, count)
          .mapToObj(n -> "%s-%05d".formatted(run, n))
          .forEach(number -> futures.add(executor.submit(() -> cardFor(number, settled, count))));
    }
    var lines = new ArrayList<String>(count + 1);
    lines.add(HEADER);
    futures.stream()
        .map(Future::resultNow)
        .flatMap(Optional::stream)
        .map(this::line)
        .forEach(lines::add);
    Files.createDirectories(cardsFile.getParent());
    Files.write(cardsFile, lines);
    var written = lines.size() - 1;
    log.info(
        "Load-test run {} wrote {} cards of {} to {} in {} s",
        run,
        written,
        count,
        cardsFile,
        Duration.between(started, Instant.now()).toSeconds());
    return new Result(count, written, cardsFile.toString());
  }

  // Sends one Invoice, approves it and waits for its Payment; the card Globex received once it
  // completed, or empty. A failure here ends only this Invoice, logged without the platform's
  // message.
  private Optional<ReceivedCard> cardFor(String invoiceNumber, AtomicInteger settled, int count) {
    try {
      var invoiceId =
          initech
              .submitInvoice(
                  invoiceNumber, new Money().minorUnits(AMOUNT).currency(Money.CurrencyEnum.USD))
              .getId();
      initech.approve(invoiceId);
      var payment = awaitPayment(invoiceId);
      if (payment == null || payment.getState() != Payment.StateEnum.COMPLETED) {
        log.warn(
            "Load-test Invoice {} has no card: its Payment is {}",
            invoiceNumber,
            payment == null ? "not started" : payment.getState());
        return Optional.empty();
      }
      // Globex opened the link before the platform marked the Payment complete.
      var card = globex.cardFor(invoiceId);
      if (card.isEmpty()) {
        log.warn("Load-test Invoice {} was paid, but Globex has no card for it", invoiceNumber);
      }
      return card;
    } catch (RestClientResponseException refused) {
      log.warn(
          "Load-test Invoice {} has no card: the platform answered {}",
          invoiceNumber,
          refused.getStatusCode().value());
      return Optional.empty();
    } catch (RestClientException failed) {
      log.warn(
          "Load-test Invoice {} has no card: {}", invoiceNumber, failed.getClass().getSimpleName());
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } finally {
      var done = settled.incrementAndGet();
      if (done % PROGRESS_EVERY == 0) {
        log.info("Load-test Invoices settled: {} of {}", done, count);
      }
    }
  }

  // Reads the Invoice until its Payment is COMPLETED or FAILED, for at most PAYMENT_WAIT; returns
  // the Payment as last read.
  private Payment awaitPayment(UUID invoiceId) throws InterruptedException {
    var deadline = Instant.now().plus(PAYMENT_WAIT);
    var payment = initech.find(invoiceId).getPayment();
    while (!settled(payment) && Instant.now().isBefore(deadline)) {
      Thread.sleep(POLL_INTERVAL);
      payment = initech.find(invoiceId).getPayment();
    }
    return payment;
  }

  private static boolean settled(Payment payment) {
    return payment != null
        && (payment.getState() == Payment.StateEnum.COMPLETED
            || payment.getState() == Payment.StateEnum.FAILED);
  }

  // One card as the load test reads it: PAN, security code, expiry YYMM, amount in minor units,
  // currency, and the Acceptor ID Globex's Charges must carry.
  private String line(ReceivedCard card) {
    return String.join(
        ",",
        card.pan(),
        card.securityCode(),
        card.expiryMonth(),
        Long.toString(card.amount().getMinorUnits()),
        card.amount().getCurrency().getValue(),
        globexAcceptorId);
  }

  /** What the run did: Invoices sent, cards written, and the file's absolute path. */
  record Result(int requested, int written, String file) {}
}
