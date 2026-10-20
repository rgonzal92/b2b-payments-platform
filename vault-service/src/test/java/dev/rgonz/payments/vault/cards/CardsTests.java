package dev.rgonz.payments.vault.cards;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.payments.vault.TestcontainersConfiguration;
import dev.rgonz.payments.vault.openbao.Transit;
import java.time.Instant;
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

/** The Card Vault gives each card a PAN no other card has. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CardsTests {

  @Autowired JdbcClient jdbc;
  @Autowired Transit transit;
  @Autowired CardHashing hashing;

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
    var cards = new Cards(jdbc, transit, hashing, random);
    var firstCard = "card-" + UUID.randomUUID();
    var secondCard = "card-" + UUID.randomUUID();

    var first = cards.issue(firstCard, Instant.now());
    var second = cards.issue(secondCard, Instant.now());

    assertThat(first.lastFour()).isEqualTo("7899");
    assertThat(second.lastFour()).isEqualTo("3219");
    assertThat(
            jdbc.sql("select count(*) from card where card_token in (:first, :second)")
                .param("first", firstCard)
                .param("second", secondCard)
                .query(Long.class)
                .single())
        .isEqualTo(2);
  }

  /** Hands out the given numbers in order, so a test decides what the Card Vault draws. */
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
      throw new UnsupportedOperationException("The Card Vault draws with nextInt(bound) only.");
    }
  }
}
