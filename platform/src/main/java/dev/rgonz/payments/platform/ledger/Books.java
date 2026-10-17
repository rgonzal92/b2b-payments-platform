package dev.rgonz.payments.platform.ledger;

import java.util.List;

/** How other modules check the Issuer's books balance (ADR-0015), without reading the journal. */
public interface Books {

  /**
   * The books' balance in each currency the journal has an entry in, by currency code, as the
   * journal stands now; none while it is empty. Reads every line, so it is for a daily check, not a
   * request path. Joins the caller's transaction, so a caller reading other modules too can read
   * them all from one snapshot.
   */
  List<BooksBalance> balances();
}
