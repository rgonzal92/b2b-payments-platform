package dev.rgonz.payments.platform.buyers;

import dev.rgonz.payments.platform.shared.Money;
import java.util.UUID;

record Buyer(UUID id, String name, Money approvalThreshold) {}
