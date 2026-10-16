package dev.rgonz.payments.platform.payables;

import java.time.Instant;

/** One Approver's approval or rejection of an Invoice. */
record Decision(Approver approver, Outcome outcome, Instant decidedAt) {

  enum Outcome {
    APPROVED,
    REJECTED
  }
}
