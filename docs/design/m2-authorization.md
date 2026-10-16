# M2: Authorization

**Status:** approved 2026-10-16; amended 2026-10-16 for a Reversal that arrives before its Authorization.

## Problem

M1 ends with Globex holding a Single-Use Card it can't use: nothing can charge a card yet. M2 builds the second half of the core path (README, step 5): Globex charges the card through its Acquirer, the Network asks the platform to approve or decline, and the platform answers in real time by the Authorization rules (Q19), cancels an Authorization when the Network sends a Reversal, and expires cards nobody charged. It's the first real-time, latency-bound path in the system and the first non-REST protocol, so the contract and the rules have to be right before M5 extracts Authorization into its own service.

## Done when

1. Globex charges its Single-Use Card through the simulator's Acquirer and Network roles; the Network asks the platform over gRPC and gets an approval. The demo endpoint does all of M1 plus the Charge in one call.
2. Each Authorization rule declines with its own response code: wrong Supplier, amount above the card's, a second Charge, an expired or cancelled card, a wrong security code. Every decline leaves the card and the Hold unchanged.
3. The same Authorization request sent twice (same request ID) gets the same answer, and only one Authorization is recorded.
4. When the platform doesn't answer within 1 second, the Network declines the Charge and sends a Reversal; the Reversal cancels the platform's Authorization, even one the platform approves after the Reversal arrives; the card can be charged again, and its Hold stays. A Reversal for a declined Authorization is acknowledged and changes nothing.
5. A Single-Use Card nobody charged within 30 days expires: its Hold is released and its Payment ends `EXPIRED`.
6. Only the Network can call the Authorization service: a call without a valid Network token is refused.
7. The end-to-end test runs the core path through the running simulator: both apps, real Keycloak and PostgreSQL, from Invoice to approved Charge.
8. `docs/architecture.md`, explainers for gRPC/protobuf and the Authorization message, and runbooks for what M2 makes possible are written.

## Approach

### Modules in M2

| Module | Builds |
|---|---|
| `authorization` | The gRPC Authorization service: the rules, Authorization records, Reversals. New. |
| `cards` | Card status after a Charge (`AUTHORIZED`), back to `ACTIVE` on Reversal, `EXPIRED` after 30 days; the expiry job. |
| `vault` | Find a card by PAN (through the keyed hash) and check a security code, without returning either. |
| `suppliers` | A Supplier's Acceptor ID. |
| `payables` | The Payment's `EXPIRED` end state; releases an expired card's Hold (changed during #34). |
| `ledger` | Unchanged: an expired card's Hold is released through `Holds.release`. |

### The Network link: gRPC with ISO 8583 field names (ADR-0006)

- **The contract is a `.proto` file in `contracts`**, beside the OpenAPI document: an `IssuerAuthorization` service with `Authorize` and `Reverse`. Spring Boot 4.1's own gRPC starters (`spring-boot-starter-grpc-server` in the platform, `-client` in the simulator) and Boot's managed `protobuf-maven-plugin` generate the code. (Changed during #30: the file is `contracts/src/main/proto/issuer_authorization.proto`, where Spring Boot's parent POM points the plugin, rather than beside the OpenAPI document under `src/main/resources`; proto package `payments.network.v1`, Java package `dev.rgonz.payments.contracts.network`.)
- **Field names follow ISO 8583 data elements,** so the M5/bonus move to real ISO 8583 is a mapping, not a redesign: PAN (DE 2), amount and currency (DE 4, DE 49), transmission time (DE 7), STAN (DE 11), card expiry (DE 14), category code (DE 18), retrieval reference number (DE 37, the request ID), Acceptor ID and name (DE 42, DE 43), security code; the answer carries a response code (DE 39) and, on approval, an authorization code (DE 38). (Changed during #30: the currency travels as the ISO 4217 alphabetic code, `USD`, like `Money`, where real ISO 8583 carries the numeric `840` in DE 49; the mapping is a lookup.)
- **The platform serves gRPC on `127.0.0.1:9090`** next to its REST port.
- **The Network authenticates with a Keycloak token:** a `network` client (client credentials, realm role `network`), sent as a bearer token in gRPC metadata and checked by Spring's gRPC security support, like every REST call. Mutual TLS arrives in M7. (Changed during #30: the rule covers every RPC on the server, so the reflection and health services the starter adds also need the Network's token.)

### Authorization rules (Q19, with ADR-0011)

For a Single-Use Card the Hold was placed at issuance, so Authorization checks the card, not Available Credit. Approve only if all are true; otherwise decline with the matching response code:

| Rule | Decline code |
|---|---|
| The PAN belongs to a card the Issuer issued | `14` invalid card |
| The card is `ACTIVE` and the expiry in the message matches and hasn't passed | `54` expired card |
| The security code matches | `N7` security code mismatch |
| The Charge's Acceptor ID is the card's Supplier's | `57` not permitted to this Supplier |
| The amount is no more than the card's amount, in its currency | `61` exceeds amount |
| The card has no other approved Authorization | `57` (already charged) |

- **An approval** records the Authorization, moves the card to `AUTHORIZED` and answers `00` with an authorization code. The Hold is untouched: Clearing (M3) turns it into posted spend.
- **The same request ID twice** returns the stored answer (a unique constraint, not check-then-insert), whatever the rules would say now.
- (Changed during #31: two checks guard the trust boundary ahead of the table. A request without a retrieval reference number is refused with gRPC `INVALID_ARGUMENT` and recorded nowhere, since there is no request ID to record it under or to answer a resend with; a zero or negative amount answers `13` (invalid amount) and is recorded. The currency is compared as sent, so a code that isn't ISO 4217 answers `61` and is recorded, as text, instead of failing the call. The expiry rule declines a `CANCELLED` or `EXPIRED` card rather than any card that isn't `ACTIVE`: an `AUTHORIZED` card passes it and answers `57` at the last rule, so a second Charge is told it's a second Charge. It reads DE 14 as the card's expiry month in UTC, and a card's expiry has passed from its expiry instant on, as the expiry job counts it. The rules are a pure decision, `AuthorizationRules`, on the card, its Supplier's Acceptor ID, the vault's security-code verdict and the Charge; the answer is recorded with `insert … on conflict (request_id) do nothing` before the card moves, and an approval whose card another request ID moved first is recorded as `57`.)
- **A Reversal** names the original request ID. If it approved, the Authorization becomes `REVERSED` and the card goes back to `ACTIVE`; if it declined, nothing changes. If the platform hasn't seen that request ID yet (the Network gave up while the Authorization was still running), it records the request ID as reversed, so the late Authorization hits the unique constraint and answers `05` (do not honour) instead of approving a Charge the Network already declined. Reversals are idempotent too.
- **The PAN stays out of everything but `vault`:** `authorization` hands the PAN and security code straight to `vault` (`cardFor(pan)`, `securityCodeMatches(token, code)`), never stores or logs them, and tracing records the RPC name only. ADR-0004 gains `authorization` beside `suppliers` as a module that handles a PAN in memory. (Changed during #30: Micrometer's gRPC observation also records the call's status and the caller's address and port, still never message fields or metadata. The Authorizations live in `"authorization".authorizations`: `authorization` is a reserved word in PostgreSQL, so SQL quotes the schema's name, and the table's plural name needs no quotes.)

### A Supplier's Acceptor ID

The Network identifies the Supplier on every Charge by the Acceptor ID its Acquirer gave it (ISO 8583 DE 42). The Invoice's Supplier block gains an `acceptorId`, stored on the Supplier like the remittance address (first Invoice sets it, ADR-0012). A card whose Supplier has no Acceptor ID can't be charged (`57`). (Changed during #32: the first Invoice that *carries* an Acceptor ID sets it. The field is optional, so a Supplier first named without one takes the first one a later Invoice brings; once set, a different one on a later Invoice is ignored without an error, like the remittance address. A Supplier created before this change, such as Globex in a local database, picks it up from the next Invoice that names it.)

- Decided during #32:
  - The contract's `acceptorId` is 1 to 15 characters (DE 42's length), not blank, and compared exactly. Invoices without it are still accepted.
  - `authorization` reads it through `suppliers.Suppliers.acceptorId(supplierId)`, a new direct call from `authorization` to `suppliers`. The rule runs where the rule table puts it; until #31 adds the security-code check, that is right after the card-status check, so `authorization` reads the card (`cards.Cards.find`) before the conditional update that moves it to `AUTHORIZED`. (Changed during #31: the check is now one of `AuthorizationRules`, between the security-code and amount rules, as the table orders them.)
  - Globex's Acceptor ID is the simulator setting `simulator.globex.acceptor-id` (a made-up 15-character value). Acme's ERP sends it on its Invoices, and the Acquirer role (#36) will put the same setting on Globex's Charges.

### Card expiry

A scheduled job in `cards` finds `ACTIVE` Single-Use Cards past their expiry, marks them `EXPIRED`, releases their Hold and tells `payables`, whose Payment ends `EXPIRED`. Re-paying the Invoice is out of scope. (Changed during #34: `cards` publishes `CardExpired` and `payables` releases the Hold through `Holds.release`, in the same transaction that ends the Payment. `payables` already owns the Hold's lifecycle, placing and releasing it in the saga, and `cards` then needs no dependency on `ledger`.) The 7-day "authorized but never cleared" rule (Q40) waits for Clearing in M3.

### Simulator: Acquirer and Network

- **Globex keeps the card details** it reads through the one-time link (in memory only, never logged) so it can charge them; the demo endpoint charges right after the Payment completes.
- **The Acquirer role** turns Globex's Charge into an Authorization request with Globex's Acceptor ID and a fresh request ID.
- **The Network role** calls the platform with a 1-second deadline, signed in as `network`. On a deadline or an unreachable platform it declines the Charge to Globex and sends a Reversal for the request. (Changed during #36: the deadline, the decline and the Reversal come with #37. Until then the Network sends the request with no deadline and returns the platform's answer, and a call that fails, such as to an unreachable platform, fails the demo's request.)
- Decided during #36:
  - The simulator calls the platform through Spring Boot's `spring-boot-starter-grpc-client`: a channel named `platform`, whose target is `static://` plus the simulator setting `simulator.platform-grpc-address` (`localhost:9090` by default), plaintext until M7.
  - The Network signs in through the same `OAuth2AuthorizedClientManager` as the simulator's REST clients, with a `network` client registration whose secret is `NETWORK_CLIENT_SECRET` from `.env`, so it reuses its token until the token expires. Spring gRPC's `BearerTokenAuthenticationInterceptor` puts the token in the `authorization` metadata of every call. Spring gRPC's own `ClientCredentialsTokenSupplier` was rejected: it asks Keycloak for a new token on every call.
  - The Acquirer (`GlobexAcquirer`) fills the request from the card Globex read through its link: its PAN, security code, amount and currency, and its expiry month as DE 14 (`YYMM`, in UTC, as the rules read it). It adds the time sent, a STAN that counts from `000001`, a retrieval reference number of 12 random letters and digits (`SecureRandom`, so it stays unique when a restart sets the STAN back to the start), the made-up category code `5085`, Globex's Acceptor ID (`simulator.globex.acceptor-id`) and the acceptor name `Globex`. Globex always charges a card's full amount.
  - Globex's record of the card now holds its PAN and security code, and its `toString` leaves both out. The demo's Summary shows a separate copy without them (last four digits, expiry, amount). The demo charges only a Payment that is `COMPLETED`, and the Summary gains `charge`: `approved`, `responseCode` and `authorizationCode` (null on a decline).
  - `CorePathEndToEndTests` now runs on to the approved Charge: it points the simulator's channel at the platform's free gRPC port and gives the simulator the `network` client's test secret.

### End-to-end test

A new `e2e` Maven module starts both Spring Boot apps in one JVM against Testcontainers' PostgreSQL and Keycloak (the repository's realm file) and runs the core path through the simulator's demo endpoint, from Invoice to approved Charge. No container images to build, and CI runs it like any other test. (Changed during #35: it runs M1's path first, up to Globex opening its card through the link; the Globex-charges ticket extends it to the approved Charge.)

- Decided during #35:
  - `CorePathEndToEndTests` starts the platform, then the simulator, with `SpringApplicationBuilder`, each on a free port picked beforehand and with its settings as command-line arguments: Keycloak's and PostgreSQL's addresses, test-only secrets, the simulator's platform URL. The simulator's start returns once its seeder has set up Acme.
  - The test works around one shared classpath in its own configuration, without changing either app: each app reads the `application.properties` in its own jar, the simulator leaves out the platform's DataSource and Spring Modulith event auto-configurations, and OTLP export is off.
  - The apps' executable jars carry the `exec` classifier, so the plain jars that `e2e` depends on stay the main artifacts.

## Deferred

The 100 ms latency target is measured in M4 with Gatling; the 7-day no-Clearing rule and Clearing itself are M3; multi-use cards, stand-in processing and real ISO 8583 over TCP are bonus milestones; mutual TLS is M7.

## Rejected options

- **Credit check at Authorization:** for a Single-Use Card the credit was reserved at issuance (ADR-0011); checking again could decline a Charge on an approved, funded Invoice.
- **Matching the Supplier by name:** names differ between ERP and Acquirer records; an exact Acceptor ID is the only reliable lock.
- **No authentication on the Network link until M7:** every caller of the platform already proves who it is; gRPC shouldn't be the exception.
- **Ignoring a Reversal for an unknown request ID:** when the platform is slow, the Reversal can commit before the Authorization it cancels; the late approval would then stand for a Charge the Network declined, and the card would stay `AUTHORIZED` for good.
- **End-to-end test against container images:** building images in CI adds minutes and a buildpack setup M6 brings anyway; two apps in one JVM test the same path.

## Risks

- **gRPC and Spring Security together are new here.** The bearer-token check on gRPC is proven first, in the first ticket.
- **Two Spring Boot apps in one JVM** may clash on shared static state (logging, metrics, OpenTelemetry). If they do, the fallback is the simulator in-process and the platform as a Testcontainers-built image. (Settled during #35: they share static state and one classpath, but the test's own properties cover every clash, so no fallback is needed. `docs/learning/testcontainers.md` lists them.)
- **The Acceptor ID changes the Invoice contract** that M1 shipped; it's an optional field, so existing callers keep working.
