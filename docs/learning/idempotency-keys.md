# Idempotency keys

## The problem it solves here

A Buyer's ERP sends an Invoice and the connection drops before the answer arrives. It can't tell whether the platform received it, so it sends it again. Without protection that's two Invoices and, later, two Payments for one bill. An `Idempotency-Key` header lets the platform recognise the resend: the ERP picks a new random key for each new Invoice and reuses it on every retry of that Invoice.

## Mental model

- **The key names one attempt to do one thing.** Same key, same request: the platform returns the response it gave the first time (same status, same body) and does nothing else.
- **Same key, different request is a client bug,** answered `422`. Replaying the old response would hide it; acting on the new request would make the key mean two things.
- **A key is not a duplicate check.** A new key with the same Supplier and Invoice number is a business duplicate, answered `409` by a unique constraint on the Invoice. The two rules catch different mistakes: a retry, and an ERP that sends the same bill twice as two attempts.
- **Keys are kept per Buyer** (`payables.idempotent_request`, primary key Buyer plus key). A global key space would let one Buyer receive another Buyer's stored response by reusing its key.
- **The request is compared by hash.** The row stores a SHA-256 of the request body as the platform parsed it, so a resend with different spacing still matches.

## How it's built

`IdempotentRequests.once` runs in one transaction:

1. **Claim the key:** `insert ... on conflict (buyer_id, idempotency_key) do nothing`. One row inserted means this request owns the key.
2. **Owner:** run the action (find or create the Supplier, insert the Invoice), store the response in the same row, commit.
3. **Not the owner:** read the row; a different hash is `422`, the same hash replays the stored response.

A resend that arrives while the first request is still running waits on step 1: PostgreSQL holds the second insert until the first transaction ends. If the first commits, the resend replays its response; if it rolls back, the resend runs as if it were first. No check-then-insert race, no lock written by hand.

## Gotchas we hit

- **Refusals aren't stored.** A `400` is refused before the key is claimed, and a `409` throws inside the transaction, so the claimed key rolls back with it. Resending gets the same refusal again, worked out afresh.
- **The response is stored, not rebuilt.** Rebuilding it from the Invoice would change once approvals move its status, and the replay must match what the first caller saw.
- **Keys don't expire yet.** Real APIs drop them after a day or so; a clean-up job can come when the table's size matters.
