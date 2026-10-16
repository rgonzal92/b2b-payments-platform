package dev.rgonz.payments.platform.payables;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;
import java.time.LocalDate;
import java.util.UUID;

record Invoice(
    UUID id,
    BuyerId buyerId,
    UUID supplierId,
    String invoiceNumber,
    Money amount,
    LocalDate dueDate,
    Status status) {

  enum Status {
    RECEIVED
  }
}
