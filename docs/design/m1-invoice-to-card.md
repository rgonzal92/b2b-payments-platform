# M1: Invoice to card

**Status:** approved 2026-10-15.

## Problem

M0 left two empty apps. M1 builds the first half of the core path (README, steps 1 to 4): a Buyer's ERP sends an Invoice, Approvers approve it, and the platform pays it by reserving credit, issuing a Single-Use Card and sending the Supplier a Remittance Advice with a one-time link to the card details. It's also the first milestone where money, card data and other companies' data are at stake, so identity, idempotency and the saga have to be right from the first line.

## Done when

1. The simulator's ERP role submits an Invoice. Resending it with the same `Idempotency-Key` returns the original response; the same Supplier and Invoice number under a new key is rejected as a duplicate.
2. Approvers of that Buyer approve it: one Approver at or under the Approval Threshold, two different Approvers above it. Users of another Buyer get `404` for it, never its data.
3. The final approval starts a Payment that places a Hold, issues a Single-Use Card (PAN encrypted in `vault`, Card Token everywhere else) and delivers the Remittance Advice to the Supplier. The Supplier opens the link once; a second open is refused.
4. Not enough Available Credit fails the Payment and leaves the Invoice approved and unpaid. A Remittance Advice that can't be delivered after its retries cancels the card and releases the Hold.
5. Stopping the platform mid-Payment and starting it again completes the Payment with exactly one Hold and one card.
6. One trace in Grafana spans the simulator and the platform for an Invoice submission (the check deferred from M0).
7. Tests: unit tests for every rule above; a Testcontainers integration test per module; a core-path test against real Keycloak and PostgreSQL; the Spring Modulith verification stays green; no PAN appears in any table outside `vault` or in any log line.
8. `docs/architecture.md` describes the modules as built, with explainers and runbooks for what M1 introduces.

## Approach

### Modules in M1

| Module | Builds |
|---|---|
| `shared` | `Money` (minor units plus currency), `BuyerId`, the idempotency helper. An open module every other module may use. (Changed during #15: the idempotency helper lives in `payables`, beside the table it uses, while Invoice intake is its only user; it moves here when a second module needs it.) |
| `buyers` | Buyers and their Approval Thresholds; Issuer staff create them. |
| `suppliers` | Suppliers and Supplier Relationships from Invoice data (ADR-0012); Remittance Advice and its one-time link. |
| `payables` | Invoice intake, the duplicate check, approvals, and the Payment saga. |
| `ledger` | Credit Lines, Holds, Available Credit. Postings and the double-entry journal arrive with Clearing in M3. |
| `cards` | Single-Use Card issuance and Card Tokens. |
| `vault` | PANs and security codes, encrypted (ADR-0004). |

`authorization`, `clearing` and `reconciliation` stay out until M2 and M3.

### Identity and keeping Buyers apart

- **Keycloak 26.8.1** joins `compose.yaml` on `127.0.0.1:8180` in development mode, importing a `payments` realm from a file in the repository.
- **Three roles:** `approver` (people at a Buyer), `erp` (a Buyer's ERP, signing in as a machine with client credentials) and `issuer-admin` (Issuer staff).
- **Issuer staff choose a Buyer's ID when creating it** (`PUT /v1/buyers/{buyerId}`), so the same ID can be written into that Buyer's clients in Keycloak ahead of time. Repeating the same request answers `200`; a different Buyer under an existing ID is `409`, so the endpoint never silently edits a Buyer. (Changed during #13: the ticket said `POST /v1/buyers`, which would leave Keycloak no way to know the ID in advance.)
- **Every Buyer-side token carries a `buyer_id` claim.** The platform is an OAuth2 resource server; every Buyer-side query filters by that claim, and a resource belonging to another Buyer answers `404` so its existence doesn't leak.
- **Client secrets never enter the repository.** A small script writes random development secrets into the gitignored `.env`; Docker Compose hands them to Keycloak (the realm file references them as placeholders), and both apps import `.env`.

### API contracts, written first

- The REST API is written as an OpenAPI document in a new `contracts` Maven module, reviewed before any code (Q34).
- `openapi-generator` (7.26.0) generates the Spring server interfaces the platform implements and the Java client the simulator calls, so both sides compile against the same contract.
- Conventions as in `CLAUDE.md`: `/v1` paths, RFC 9457 problem details (a `401` too, with `WWW-Authenticate` kept), `Idempotency-Key` on creating POSTs, cursor paging.

### Invoice intake

- `POST /v1/invoices` (role `erp`) carries the Supplier (name, tax ID, the Buyer's own ID for it, a remittance address), the Invoice number, the amount and a due date. The Buyer comes from the token.
- **Idempotency:** the key, a hash of the request and the stored response live in `payables`' schema. Same key and same body replays the response; same key with a different body is `422`.
- **Duplicates:** the same Supplier and Invoice number under a new key is `409`.
- The first Invoice carrying an unknown tax ID creates the Supplier; the first Invoice from a Buyer to that Supplier creates the Supplier Relationship.
- Decided during #15:
  - Keys are kept per Buyer, so one Buyer reusing another's key gets its own Invoice, never the other's response.
  - A refused request (`409`, a validation `400`) stores nothing under its key; resending it is refused the same way.
  - A token whose `buyer_id` is missing or names no known Buyer is `403`, checked through `buyers`.
  - A Supplier's name and remittance address come from the first Invoice naming its tax ID, and a Supplier Relationship's reference from that Buyer's first Invoice to it. Later Invoices don't change them, and a different name or address on a later Invoice is ignored without an error. This stops any Buyer's ERP redirecting Remittance Advice for a Supplier others already use, but the first ERP to name a tax ID sets the address every later Buyer's Remittance Advice goes to. Neither "first wins" nor "last wins" is safe on its own: Issuer staff verifying Supplier details closes that gap, and isn't built yet. (Approved by the owner; Supplier verification belongs to the Supplier acceptance milestone.)

### Approvals

- `POST /v1/invoices/{id}/approval` and `.../rejection` (role `approver`, same Buyer).
- One Approver at or under the Approval Threshold, two different Approvers above it; the same Approver twice counts once. A rejection is final.
- Each approval and rejection is recorded with who and when; that record is the audit trail for M1.
- The final approval publishes `InvoiceApproved` through Spring Modulith (ADR-0008), which creates a Payment.
- Decided during #16:
  - An Approver is the token's subject (`sub`), stored with its `preferred_username` so the record reads without Keycloak. The simulator's demo Approvers are the service accounts of two Keycloak clients, `acme-approver-1` and `acme-approver-2`.
  - `payables` gets the Approval Threshold from `buyers.BuyerDirectory`, never from `buyers`' tables.
  - The Invoice stays `RECEIVED` until it is `APPROVED` or `REJECTED`; above the threshold its first approval shows only in its `decisions`, so no partly-approved state is needed.
  - Neither action takes an `Idempotency-Key`: the same Approver approving again changes nothing and returns the Invoice. A rejection is final, so every later decision, the same Approver's repeated rejection included, is `409`; a client unsure whether its rejection arrived reads the Invoice.
  - An Approver may reject an Invoice they approved while it still awaits a second approval; the record then shows both of their decisions.
  - An approved Invoice takes no new decisions (`409`), a rejection included, because its Payment may already be under way. (Approved by the owner: the ticket named only a new Approver's approval.)
  - Concurrent decisions on one Invoice take turns on its row lock (`select ... for update`), so two Approvers can't both be counted as the first.
  - `InvoiceApproved` carries the Invoice, Buyer, Supplier and amount, which is what the saga needs to place the Hold and issue the card.

### The Payment saga

`payables` orchestrates each Payment (ADR-0013). The Payment is a row with a state; a worker inside the platform claims due Payments with `SELECT ... FOR UPDATE SKIP LOCKED`, runs the next step and saves the new state in the same transaction.

| State | Next step | If the step fails for good |
|---|---|---|
| `STARTED` | Place the Hold (`ledger`) | Not enough credit: the Payment is `FAILED`, the Invoice stays approved and unpaid |
| `HOLD_PLACED` | Issue the card (`cards`, `vault`) | Release the Hold, then `FAILED` |
| `CARD_ISSUED` | Deliver the Remittance Advice (`suppliers`) | Cancel the card, release the Hold, then `FAILED` |
| `COMPLETED` | none | |

- **Every step is idempotent, keyed by the Payment ID:** a second "place the Hold" for the same Payment returns the existing Hold. That makes crash-and-retry safe.
- **Business refusals compensate at once; technical errors retry** with backoff (5 attempts), then fail for good.
- **A crashed worker loses nothing:** its claimed row is released when the transaction rolls back, and the next pass picks it up.
- Decided during #17:
  - `InvoiceApproved` reaches the saga through an `@ApplicationModuleListener` in `payables`, which inserts the Payment unless its Invoice has one (a unique `invoice_id`), so a redelivered event starts no second Payment. Incomplete publications are delivered again at startup (`spring.modulith.events.republish-outstanding-events-on-restart=true`, off by default). The setting applies to every listener, not only the saga's. (Approved by the owner.)
  - The Payment row copies the Buyer and amount from the event, and carries `attempts` (failed attempts at the current step) and `next_attempt_at` (null once no step is left to run). A Payment stopped at `HOLD_PLACED` until #18 added card issuance.
  - Each worker transaction claims one Payment (`limit 1`); a pass repeats that up to ten times, every second. Claiming a batch in one transaction would let one Payment's failure roll back the rest, and could deadlock two workers that lock two Buyers' Credit Lines in opposite orders. (Approved by the owner: the ticket's plan said a small batch per claim.)
  - A technical error anywhere in that transaction, a lost connection included, rolls it all back; a new transaction then counts the failed attempt, unless another worker has moved the Payment on meanwhile (the update checks the state and attempt count it claimed). Retries come 10, 20, 40 and 80 seconds after each failure; the fifth fails the Payment for good. A first version used a savepoint instead, which can't be rolled back to once the connection is gone.
  - A Payment that fails says why (`failureReason`), shown with its state on `GET /v1/invoices/{invoiceId}`. Technical errors are logged with the Payment's ID, not shown to the Buyer.
- Decided during #18:
  - Placing the Hold makes card issuance due at once, so one worker pass usually runs both steps, each in its own transaction. A Payment stops at `CARD_ISSUED` until #19 adds the Remittance Advice.
  - The Payment row copies the Supplier from `InvoiceApproved` too, since the card is locked to it. The migration fills it in for existing Payments from their Invoice, and makes Payments left at `HOLD_PLACED` by #17 due for card issuance.
  - Card issuance has no business refusal, so it fails for good only on its fifth technical error. The failure reason names the step that failed ("Issuing the Single-Use Card failed 5 times for technical reasons.").
  - Compensation runs in the transaction that records the final failed attempt: the Payment becomes `FAILED` and its Hold is released together, so it is never `FAILED` with credit still reserved. If that transaction fails, the attempt isn't counted and the next pass tries the step again; releasing twice is harmless.
- Decided during #19:
  - Issuing the card makes delivering the Remittance Advice due at once, so one worker pass usually runs all three steps, each in its own transaction. A delivered advice completes the Payment (`COMPLETED`), shown on the Invoice. A migration makes Payments left at `CARD_ISSUED` by #18 due for delivery.
  - Delivery has no business refusal either: any answer but 2xx, a timeout or a refused connection is a technical error, retried with the same backoff. Its fifth failure fails the Payment ("Delivering the Remittance Advice failed 5 times for technical reasons.") and, in the same transaction, cancels the Single-Use Card (`cards.Cards.cancel`, status `CANCELLED`, harmless to repeat) and releases the Hold.

### Ledger: Credit Lines and Holds

- Issuer staff set a Buyer's Credit Line.
- Placing a Hold locks the Buyer's Credit Line row, checks Available Credit (Credit Line minus open Holds; posted spend is zero until M3) and records the Hold in one transaction, so two concurrent Payments can't both take the last of the credit.
- Releasing a Hold is idempotent.
- Decided during #17:
  - Other modules place Holds through `ledger.Holds`. The caller chooses the Hold's ID, and the saga passes the Payment's, so placing it again finds the same Hold. Releasing Holds arrives with #18.
  - A Buyer without a Credit Line has no credit: placing a Hold is refused, not an error.
  - Open Holds are summed in a statement after the one that locks the Credit Line row. Under READ COMMITTED a subquery inside the locking statement keeps the snapshot from before the wait and misses a Hold committed meanwhile; the race test caught exactly that.
  - Available Credit may be negative: Issuer staff may lower a Credit Line below the open Holds, which then blocks new Holds. The contract's `availableCredit` uses a new `SignedMoney` schema without the minimum of zero.
- Decided during #18:
  - `Holds.release(holdId)` deletes the Hold's row, so releasing it again, or releasing a Hold never placed, changes nothing. Placing a released Hold again reserves the credit afresh, after the usual check, which is what rerunning a failed Payment by hand needs (see the stuck-Payment runbook). A released Hold leaves no record in `ledger` until the journal arrives with Clearing in M3; the Payment's failure reason records why. (Open for the owner's sign-off: marking the row released instead would keep that record, but placing the Hold again would then find it and reserve nothing.) (Changed during #19: money records don't disappear. Releasing marks the Hold `RELEASED` with its release time and keeps the row; only `PLACED` Holds count against Available Credit. Placing a released Hold again checks Available Credit afresh and either places it again, for the amount now asked, or is refused, so a rerun after a compensation can't issue a card with no credit reserved.)

### Cards and the vault

- **Issuing a Single-Use Card:** a Card Token (random, opaque), an expiry 30 days out, and locks to the Invoice's Supplier and amount.
- **`vault` generates the PAN** (16 digits, Luhn-valid, from a made-up `990000` range) and a security code, and stores both encrypted with AES-256-GCM under a key from `.env`. M7 replaces the key-from-config with envelope encryption.
- **A keyed hash (HMAC-SHA-256) of each PAN is stored beside it,** so `vault` can reject a duplicate PAN now, and find a card by PAN for Authorization in M2, without decrypting anything.
- Decided during #18:
  - The saga issues the card through `cards.Cards.issueSingleUse`, keyed by the Payment's ID (`payment_id` is unique), so issuing again returns the card the Payment already has, with the first issuance's details. Like `ledger.Holds`, it joins the worker's transaction: the card, its PAN and the Payment's new state commit or roll back together.
  - A Card Token is 16 random bytes from `SecureRandom` (128 bits), base64url-encoded without padding: 22 characters, never all digits, so it can't be mistaken for a PAN.
  - `cards` stores the PAN's last four digits beside the Card Token, so the Invoice can show them without asking the vault. `vault.Vault.generatePan(cardToken)` returns those four digits and nothing else of the PAN.
  - The card's expiry is the worker pass's time plus 30 days, kept to the microsecond as PostgreSQL stores it, so the card returned at issuance equals the card read back later.
  - `GET /v1/invoices/{invoiceId}` shows the Payment's `card`: its Card Token, last four digits and expiry. Never the PAN or security code.
  - Each ciphertext is a fresh 12-byte nonce followed by the AES-256-GCM output (the encrypted value and its 16-byte tag), with the Card Token as associated data, so a ciphertext copied to another card's row fails to decrypt. The PAN and the security code are encrypted separately, each under its own nonce.
  - Encryption and hashing use two separate 256-bit keys, `vault.encryption-key` and `vault.hash-key`, base64-encoded in `.env` as `VAULT_ENCRYPTION_KEY` and `VAULT_HASH_KEY` (written by `scripts/init-env.sh` with `openssl rand -base64 32`). The platform refuses to start when either is missing, isn't base64 or isn't exactly 32 bytes; the message names the property, never its value. Tests use fixed, obviously fake keys (every byte `0x01` or `0x02`) from `platform/src/test/resources/config/application.properties`, which Spring Boot loads on top of the main file instead of hiding it.
  - The PAN's hash is unique. A new PAN is inserted with `on conflict (pan_hash) do nothing`; no row inserted means another card has that PAN, so the vault draws another (up to ten draws, then a technical error). A plain insert's unique violation would abort the worker's whole transaction instead. (Open for the owner's sign-off: no test forces a collision, since none of the seams agreed for #18 can control the vault's random draws; a vault integration test with a seeded random source would.) (Changed during #19: `vault.CardSecrets` takes its random source in a constructor tests can call, and `CardSecretsTests` hands it the same PAN twice and checks the second card gets a fresh one.)
  - Only `cards` may depend on `vault`. Spring Modulith's `allowedDependencies` limits what a module may use, not who may use it, so declaring it would mean listing allowed dependencies on every other module; `ModularityTests.onlyCardsUsesTheVault` checks the rule directly on Modulith's module model instead. #19 adds `suppliers` to it for the remittance link. (Changed during #19: the test is now `onlyCardsAndSuppliersUseTheVault`; `suppliers` asks `vault.Vault.cardDetails(cardToken)` for the PAN and security code when a link is opened, and stores neither.)

### Remittance Advice

- `suppliers` creates the Remittance Advice (which Invoice, the amount, the Card Token) with a one-time link token: 256 random bits, stored only as a hash, valid for 7 days. (Changed during #19: the token is worked out from the Payment's ID with HMAC-SHA-256 under a server key instead of drawn at random; see the decisions below.)
- **Delivery:** the advice is POSTed to the Supplier's remittance address, which the simulator's Supplier role serves. Delivery is the saga's third step, so its retries and compensation are the saga's.
- **Opening the link:** `GET /v1/remittance-links/{token}` needs no login (the token is the credential). It marks the link used in the same statement that reads it, fetches the card details from `vault`, and answers with `Cache-Control: no-store`. A second open answers `410 Gone`.
- Decided during #19:
  - **The link token is worked out, not drawn at random:** base64url(HMAC-SHA-256(`remittance.link-key`, Payment ID)), 256 bits nobody can compute without the key. Delivery POSTs to the Supplier and then commits, so a crash after the Supplier received the advice but before the commit would leave the Supplier holding a random token that was never stored, and the retry would send another; a derived token is the same on every attempt. Only its SHA-256 is stored (`suppliers.remittance_advice.link_token_hash`, unique). (Changed during #19: the ticket said 256 random bits.)
  - The key is 256 bits, base64-encoded, from `REMITTANCE_LINK_KEY` in `.env` (written by `scripts/init-env.sh`). The platform refuses to start without it, like the vault's keys; `shared.SecretKeys` reads all three. Tests use a fixed fake key (every byte `0x03`).
  - **The advice is recorded before it is POSTed, in a transaction of its own** (`REQUIRES_NEW`): the Advice's ID is the Payment's, and a second delivery finds the record and sends the same link with the first expiry. So the link already works when the Supplier receives it, even if the Supplier opens it before answering. A record left by a failed attempt is harmless: the next attempt sends the same link, and a Payment that fails for good cancels its card, so the link shows nothing. (Changed during #19: the step's other writes still commit with the Payment's new state.) _Contradicts ADR-0014 ("runs the next step and saves the new state in the same transaction") for this one record, and its POST runs inside the worker's transaction; open for the owner to amend the ADR or choose otherwise._
  - The advice is the contract's `RemittanceAdvice` (its ID, the Invoice's ID and number, the amount, `cardLink`, `cardLinkExpiresAt`), POSTed as JSON with the Advice's ID as `Idempotency-Key`, through Spring's `RestClient` with a 2-second connect and 5-second read timeout and no redirects. The link sits under `platform.public-url`. An error from a refused delivery names only the status, never the Supplier's answer, which might echo the link.
  - **Opening:** a token whose hash matches no advice answers `404`, and a cancelled card's link `410`; otherwise `update ... set link_used_at = now() where link_token_hash = ? and link_used_at is null and link_expires_at > now() returning ...`, and no row answers `410`. A link that shows nothing isn't marked used, so `link_used_at` means a Supplier saw the card. The answer has the PAN, security code, the card's expiry, the amount and the Invoice number; the card's details come from `vault` at that moment and are never stored in `suppliers`. _Contradicts the letter of ADR-0004 ("every other module handles a Card Token"): `suppliers` holds the decrypted PAN in memory for that answer. The #18 note above planned it; open for the owner to amend the ADR's wording._ If the vault fails, the transaction rolls back and the link stays unused. Of two opens at once, one updates the row and the other waits for its lock, then finds it used.
  - **The simulator's Supplier role** is Globex, at `/globex/remittance-advice` (the address Acme's ERP puts on its Invoices). The platform calls it server to server without cookies, so that path is exempt from CSRF. It drops a repeat by `Idempotency-Key`, opens the link with a plain GET while handling the POST, answers 200 even when the link won't open (sending it again wouldn't help), and keeps each Invoice's last four digits, expiry and amount in memory for the demo (#20). It logs the last four digits, never the PAN, security code or link.
  - **Traces record each request's URL,** which for a link is its token. The platform records a link request by its route instead (`RequestObservations`), and Globex opens links with a client that isn't traced.

### Persistence

- Each module's Flyway migrations live in their own folder and run with their own history table inside the module's schema, so a module's schema moves with it at extraction (ADR-0009).
- Spring Modulith keeps its event publications in its own table through `spring-modulith-starter-jdbc`. (Decided during #16: the table is `modulith.event_publication`, in a schema of its own that the registry creates at startup with its table; no Flyway migration owns it.)

### Simulator

- **ERP role:** submits Invoices through the generated client, signed in as the `acme-erp` client.
- **Supplier role:** receives Remittance Advice, opens each link once and logs only the last four digits.
- **Approver role,** for demos: two service accounts, `acme-approver-1` and `acme-approver-2`, approve on request.
- **A demo endpoint** runs the whole path in one call. A Buyer named Acme is created at simulator startup through the Issuer admin API.

### Tests

- **Unit tests:** approval rules, the credit check, Luhn and PAN generation, the saga's state transitions. (Changed during #17: the credit check is a comparison inside the transaction that locks the Credit Line, so it is tested through `ledger.Holds` and the Payment flow on PostgreSQL, the test seams agreed for #17, not as a unit test. Approved by the owner.)
- **Module integration tests** on PostgreSQL Testcontainers, with mocked JWTs (`spring-security-test`).
- **A core-path test** against real Keycloak (`testcontainers-keycloak` 4.4.0, importing the same realm file) and PostgreSQL, with the Supplier's address stubbed by `MockRestServiceServer`.
- **A test that greps every table outside `vault`, and the captured logs, for the issued PAN.**
- The end-to-end test through a running simulator arrives in M2 with the Network role, as planned.

## Deferred

Card expiry processing (M2, with Authorization), postings and the journal (M3), multi-use cards, statements and repayment, the Angular UI (M9), Kafka (M5).

## Rejected options

- **Saga steps driven by chained Modulith events:** the Payment's state would be spread across event publications, and retry counts and backoff would hide in the framework. A state row plus a `SKIP LOCKED` worker keeps one place to look and makes retries explicit. Events still carry `InvoiceApproved` into the saga.
- **Code-first OpenAPI (springdoc):** the contract would follow the code instead of being agreed first, and the simulator would have to hand-write its client.
- **One Flyway instance for all modules:** version numbers would collide across module folders, and the history table couldn't move with a module.
- **Development secrets committed in the realm file:** breaks the never-commit-credentials rule even for local values.
- **WireMock for the Supplier's address:** `MockRestServiceServer` already stubs Spring's `RestClient` without a new dependency.

## Risks

- **`openapi-generator`'s Spring output may lag Spring Boot 4.** If the generated interfaces don't compile against Spring Framework 7, generate DTOs only and write the interfaces by hand. (Resolved in #13: generator 7.26 has `useSpringBoot4` and `useJackson3`; the simulator's client is generated as Spring HTTP interfaces.)
- **Keycloak placeholders in realm files** must resolve from the environment at import; check this first, before building on it. (Resolved in #13: confirmed, and `KeycloakTokenTests` checks it on every build.)
- **M1 is the largest milestone (3 weeks).** Ticket order puts identity and contracts first, so the riskiest integration surfaces early.
