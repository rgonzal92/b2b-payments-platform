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
| `shared` | `Money` (minor units plus currency), `BuyerId`, the idempotency helper. An open module every other module may use. |
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
- **Issuer staff choose a Buyer's ID when creating it** (`PUT /v1/buyers/{buyerId}`, safe to repeat), so the same ID can be written into that Buyer's clients in Keycloak ahead of time. (Changed during #13: the ticket said `POST /v1/buyers`, which would leave Keycloak no way to know the ID in advance.)
- **Every Buyer-side token carries a `buyer_id` claim.** The platform is an OAuth2 resource server; every Buyer-side query filters by that claim, and a resource belonging to another Buyer answers `404` so its existence doesn't leak.
- **Client secrets never enter the repository.** A small script writes random development secrets into the gitignored `.env`; Docker Compose hands them to Keycloak (the realm file references them as placeholders), and both apps import `.env`.

### API contracts, written first

- The REST API is written as an OpenAPI document in a new `contracts` Maven module, reviewed before any code (Q34).
- `openapi-generator` (7.26.0) generates the Spring server interfaces the platform implements and the Java client the simulator calls, so both sides compile against the same contract.
- Conventions as in `CLAUDE.md`: `/v1` paths, RFC 9457 problem details, `Idempotency-Key` on creating POSTs, cursor paging.

### Invoice intake

- `POST /v1/invoices` (role `erp`) carries the Supplier (name, tax ID, the Buyer's own ID for it, a remittance address), the Invoice number, the amount and a due date. The Buyer comes from the token.
- **Idempotency:** the key, a hash of the request and the stored response live in `payables`' schema. Same key and same body replays the response; same key with a different body is `422`.
- **Duplicates:** the same Supplier and Invoice number under a new key is `409`.
- The first Invoice carrying an unknown tax ID creates the Supplier; the first Invoice from a Buyer to that Supplier creates the Supplier Relationship.

### Approvals

- `POST /v1/invoices/{id}/approval` and `.../rejection` (role `approver`, same Buyer).
- One Approver at or under the Approval Threshold, two different Approvers above it; the same Approver twice counts once. A rejection is final.
- Each approval and rejection is recorded with who and when; that record is the audit trail for M1.
- The final approval publishes `InvoiceApproved` through Spring Modulith (ADR-0008), which creates a Payment.

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

### Ledger: Credit Lines and Holds

- Issuer staff set a Buyer's Credit Line.
- Placing a Hold locks the Buyer's Credit Line row, checks Available Credit (Credit Line minus open Holds; posted spend is zero until M3) and records the Hold in one transaction, so two concurrent Payments can't both take the last of the credit.
- Releasing a Hold is idempotent.

### Cards and the vault

- **Issuing a Single-Use Card:** a Card Token (random, opaque), an expiry 30 days out, and locks to the Invoice's Supplier and amount.
- **`vault` generates the PAN** (16 digits, Luhn-valid, from a made-up `990000` range) and a security code, and stores both encrypted with AES-256-GCM under a key from `.env`. M7 replaces the key-from-config with envelope encryption.
- **A keyed hash (HMAC-SHA-256) of each PAN is stored beside it,** so `vault` can reject a duplicate PAN now, and find a card by PAN for Authorization in M2, without decrypting anything.

### Remittance Advice

- `suppliers` creates the Remittance Advice (which Invoice, the amount, the Card Token) with a one-time link token: 256 random bits, stored only as a hash, valid for 7 days.
- **Delivery:** the advice is POSTed to the Supplier's remittance address, which the simulator's Supplier role serves. Delivery is the saga's third step, so its retries and compensation are the saga's.
- **Opening the link:** `GET /v1/remittance-links/{token}` needs no login (the token is the credential). It marks the link used in the same statement that reads it, fetches the card details from `vault`, and answers with `Cache-Control: no-store`. A second open answers `410 Gone`.

### Persistence

- Each module's Flyway migrations live in their own folder and run with their own history table inside the module's schema, so a module's schema moves with it at extraction (ADR-0009).
- Spring Modulith keeps its event publications in its own table through `spring-modulith-starter-jdbc`.

### Simulator

- **ERP role:** submits Invoices through the generated client, signed in as the `acme-erp` client.
- **Supplier role:** receives Remittance Advice, opens each link once and logs only the last four digits.
- **Approver role,** for demos: two service accounts, `acme-approver-1` and `acme-approver-2`, approve on request.
- **A demo endpoint** runs the whole path in one call. A Buyer named Acme is created at simulator startup through the Issuer admin API.

### Tests

- **Unit tests:** approval rules, the credit check, Luhn and PAN generation, the saga's state transitions.
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
