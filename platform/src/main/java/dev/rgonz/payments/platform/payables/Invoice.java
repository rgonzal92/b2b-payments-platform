package dev.rgonz.payments.platform.payables;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

record Invoice(
    UUID id,
    BuyerId buyerId,
    UUID supplierId,
    String invoiceNumber,
    Money amount,
    LocalDate dueDate,
    Status status) {

  /**
   * Whether approvals by these Approvers complete the Invoice: one Approver at or under the Buyer's
   * Approval Threshold, two different Approvers above it.
   */
  boolean isApprovedBy(Collection<String> approverIds, Money approvalThreshold) {
    var needed = amount.isMoreThan(approvalThreshold) ? 2 : 1;
    return Set.copyOf(approverIds).size() >= needed;
  }

  enum Status {
    RECEIVED,
    APPROVED,
    REJECTED
  }
}
