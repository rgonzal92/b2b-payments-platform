package dev.rgonz.payments.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/**
 * How far the copy's consumer group is behind on each partition of the {@code authorizations}
 * topic, from the offsets Kafka reports.
 */
class AuthorizationCopyLagTests {

  private static final TopicPartition P0 = new TopicPartition("authorizations", 0);
  private static final TopicPartition P1 = new TopicPartition("authorizations", 1);

  @Test
  void aGroupThatCommittedEveryPartitionsEndHasNoLag() {
    assertThat(AuthorizationCopyLag.lags(Map.of(P0, 7L, P1, 3L), Map.of(P0, 7L, P1, 3L), Map.of()))
        .isEmpty();
  }

  @Test
  void aPartitionCommittedShortOfItsEndLagsByTheDifference() {
    assertThat(AuthorizationCopyLag.lags(Map.of(P0, 7L, P1, 3L), Map.of(P0, 5L, P1, 3L), Map.of()))
        .containsExactly(Map.entry(P0, 2L));
  }

  // Retention deleted everything on the partition before the group read it: nothing is left to
  // read.
  @Test
  void anUncommittedPartitionCountsFromItsFirstRetainedOffset() {
    assertThat(AuthorizationCopyLag.lags(Map.of(P0, 7L), Map.of(), Map.of(P0, 7L))).isEmpty();
    assertThat(AuthorizationCopyLag.lags(Map.of(P0, 7L), Map.of(), Map.of(P0, 4L)))
        .containsExactly(Map.entry(P0, 3L));
  }

  @Test
  void aPartitionCommittedPastItsEndDoesNotHideAnotherPartitionsLag() {
    assertThat(AuthorizationCopyLag.lags(Map.of(P0, 7L, P1, 3L), Map.of(P0, 9L, P1, 1L), Map.of()))
        .containsExactly(Map.entry(P1, 2L));
  }
}
