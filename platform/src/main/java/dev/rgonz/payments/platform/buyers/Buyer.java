package dev.rgonz.payments.platform.buyers;

import dev.rgonz.payments.platform.shared.BuyerId;
import dev.rgonz.payments.platform.shared.Money;

record Buyer(BuyerId id, String name, Money approvalThreshold) {}
