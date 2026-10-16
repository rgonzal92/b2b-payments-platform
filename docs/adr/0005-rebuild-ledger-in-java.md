# Rebuild the ledger in Java instead of reusing core-ledger

`core-ledger` (Go) already handles double-entry posting, idempotency and append-only storage, but it has no Holds, no Available Credit, no metadata on transactions and no API contract. Adapting it would mean rewriting most of it and running a second language. We rebuild the Ledger as a Java module and carry over core-ledger's proven decisions: integer minor units, idempotency keys stored with the original answer, row locks taken in a fixed order, and append-only entries.

**Amended 2026-10-16 (#53):** for a Repayment, the stored answer is the Journal Entry itself. The request's `Idempotency-Key` is the entry's idempotency key, and a repeated request gets its reply rebuilt from that entry rather than from a stored copy of the first reply, so the reply can't disagree with the books.
