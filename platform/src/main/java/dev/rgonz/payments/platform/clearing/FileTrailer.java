package dev.rgonz.payments.platform.clearing;

import dev.rgonz.payments.platform.shared.Money;

/**
 * A Clearing File's trailer record: its control totals, which show the file arrived whole.
 *
 * @param recordCount the number of detail records
 * @param amountTotal the sum of their amounts
 * @param netSettlementAmount what the Network will settle with the Issuer for the file
 */
record FileTrailer(long recordCount, Money amountTotal, Money netSettlementAmount)
    implements ClearingFileLayout.Record {}
