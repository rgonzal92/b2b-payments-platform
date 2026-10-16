package dev.rgonz.payments.platform.vault;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.payments.platform.TestcontainersConfiguration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The vault stores each card's PAN on PostgreSQL, one card per PAN, and finds a card by its PAN.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CardSecretsTests {

  @Autowired JdbcClient jdbc;
  @Autowired VaultCrypto crypto;
  @Autowired Vault vault;

  @Test
  void aKnownPanFindsItsCardToken() {
    var cardToken = "card-" + UUID.randomUUID();
    vault.generatePan(cardToken);
    var pan = vault.cardDetails(cardToken).pan();

    assertThat(vault.cardFor(pan)).contains(cardToken);
  }

  @Test
  void anUnknownPanFindsNoCard() {
    // Outside the 990000 range the vault draws from, so no card has it.
    assertThat(vault.cardFor("4000000000000002")).isEmpty();
  }

  @Test
  void aPanAnotherCardAlreadyHasIsDrawnAgain() {
    // Each card draws its security code, then the PAN's nine random digits. The second card's first
    // PAN is the first card's, 9900001234567899; its second draw gives 9900009876543219.
    var random =
        new ScriptedRandom(
            List.of(
                123, 1, 2, 3, 4, 5, 6, 7, 8, 9, // first card
                456, 1, 2, 3, 4, 5, 6, 7, 8, 9, // second card: the same PAN
                9, 8, 7, 6, 5, 4, 3, 2, 1)); // and a fresh one
    var scriptedVault = new CardSecrets(jdbc, crypto, random);
    var firstCard = "card-" + UUID.randomUUID();
    var secondCard = "card-" + UUID.randomUUID();

    var firstLastFour = scriptedVault.generatePan(firstCard);
    var secondLastFour = scriptedVault.generatePan(secondCard);

    assertThat(firstLastFour).isEqualTo("7899");
    assertThat(secondLastFour).isEqualTo("3219");
    assertThat(
            jdbc.sql("select count(*) from vault.card_secret where card_token in (:first, :second)")
                .param("first", firstCard)
                .param("second", secondCard)
                .query(Long.class)
                .single())
        .isEqualTo(2);
  }

  /** Hands out the given numbers in order, so a test decides what the vault draws. */
  private static final class ScriptedRandom implements RandomGenerator {

    private final Queue<Integer> draws;

    ScriptedRandom(List<Integer> draws) {
      this.draws = new ArrayDeque<>(draws);
    }

    @Override
    public int nextInt(int bound) {
      var draw = draws.remove();
      assertThat(draw).as("a scripted draw below %d", bound).isLessThan(bound);
      return draw;
    }

    @Override
    public long nextLong() {
      throw new UnsupportedOperationException("The vault draws with nextInt(bound) only.");
    }
  }
}
