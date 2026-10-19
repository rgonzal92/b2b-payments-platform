# M5: Authorization becomes its own service

**Status:** approved 2026-10-18. Decisions from the M5 grilling (Rounds 1–4, Q1–Q24, 2026-10-18).

## Problem

Every Charge is answered inside the monolith, so anything that stops or slows the platform declines Charges: a redeploy, a crash, a Clearing import or a Reconciliation run competing for the same JVM and database. The Network declines any Charge not answered within 1 second and sends a Reversal (ADR-0010), so for the Network a platform restart is an outage. M5 moves Authorization into its own service, `authorization-service`, that keeps answering while the platform is down, and brings the tools that a process boundary needs: Kafka to carry events between the two, an outbox on each side so no event is lost, Resilience4j so the service fails fast when its own database fails, and contract tests so the two sides' events can't drift apart.

The force is availability, not latency (ADR-0016). M4 measured the monolith's server-side p99 at 35.6 ms on one run and 104.2 ms on the other, the miss caused by the laptop disk's commit flushes ([M4, Measured](m4-reconciliation-and-measurement.md#measured-75)), so latency alone did not justify the move.

## Done when

1. With the platform stopped, the Network still gets approvals for cards already on file with the service; an automated test stops the platform mid-run and proves it.
2. M4's Gatling simulation runs unchanged against the service, and its p50/p95/p99, errors and achieved rate are recorded in this document beside M4's.
3. One more run with both PostgreSQL data folders bind-mounted on a folder with copy-on-write off (`chattr +C`) is recorded beside it, separating the disk's effect from the code's.
4. Grafana's Authorization dashboard reads the service's metrics, with new panels for Kafka consumer lag and Payments waiting for the card to be on file, and an alert fires when a Payment waits more than 5 minutes.
5. The end-to-end test's core path runs through the service, and Clearing and Reconciliation behave as in M4.
6. A Pact consumer test and provider test cover each event in both directions.
7. `docs/architecture.md`, explainers for Kafka, Reactor and WebFlux, Resilience4j and Pact, and runbooks for Kafka down and for the service or its database down are written.

## Approach

### What moves (Q1, Q4, Q5, Q6, Q20)

| Where | Builds |
|---|---|
| `authorization-service` | New Maven module, a Spring Boot app with its own PostgreSQL container (`postgres:18.6`). The Network link over gRPC, the Authorization rules, every answer and Reversal, its copy of the cards it may authorize, and its outbox. |
| platform `authorization` | Thin. Loses the gRPC link, the rules and the answers; keeps the platform's copy of approved and reversed Authorizations, fed by the service's events. `ApprovedAuthorizations` keeps its interface, so `clearing` and `reconciliation` don't change. |
| platform `cards` | Publishes `CardIssued`, `CardCancelled` and `CardExpired` to Kafka; mirrors the service's ACTIVE/AUTHORIZED moves. |
| platform `vault` | Returns the PAN's keyed hash and a new keyed hash of the security code beside the last four digits. |
| platform `payables` | A new Payment saga step that waits for the card to be on file before sending the Remittance Advice. |
| platform `clearing` | Waits to import while the platform's copy of Authorizations lags behind Kafka. |
| `contracts` | Keeps the Network link's proto, now served by the service. |
| `simulator`, `load-test` | Unchanged: the service listens where the platform did, `127.0.0.1:9090`. |

The monolith's answering code is deleted rather than run beside the service: the simulator's Network is the only caller and local data is disposable, so the README tells readers to reset their volumes. The service's database is its own container so a batch job can't take its connections or I/O, though on a laptop both containers still share one disk.

### The cards the service holds (Q2, Q7, Q8, Q9, Q21)

To answer without the platform, the service keeps its own copy of every card it may authorize, fed by the platform's events on the `cards` topic:

- **`CardIssued`:** Card Token, the PAN's keyed hash, the security code's keyed hash, expiry, amount and currency, and the Supplier's Acceptor ID. The saga, which already asks `suppliers` for the Supplier, passes the Acceptor ID into `cards.issue(...)`, since `cards` can't call `suppliers` (`suppliers` already calls `cards`, and Spring Modulith forbids the cycle). `cards` publishes the event in its issuing transaction. The Acceptor ID is copied at issuance, as on the Remittance Advice; a Supplier that changes Acquirer after its card is issued is declined.
- **`CardCancelled`**, new, and **`CardExpired`**, which already exists and is now externalized.
- No event for a cleared card: the service's AUTHORIZED already declines a second Charge with `57`, the answer a CLEARED card gets today.

The service holds the platform's `vault.hash-key` from its own environment variable, hashes each request's PAN and security code in memory and compares; no PAN or security code goes onto Kafka or into either event table (ADR-0004, amended).

Card status is split. The platform issues, cancels, expires and clears cards; the service alone moves a card between ACTIVE and AUTHORIZED (an approval, and back on a Reversal), in one transaction with the answer, exactly as `Authorizations.authorize` does today. The platform's thin `authorization` module stores each approved or reversed Authorization and moves the platform's card through `cards` in one transaction, so the 7-day expiry still finds AUTHORIZED cards. A card the platform expires on day 30 while the service approves a Charge on it stays EXPIRED in the platform; its Clearing takes the existing `AFTER_HOLD_EXPIRED` outcome and Break.

### Events and contracts (Q3, Q12, Q15, Q16, Q22)

- **Topics:** `cards` (platform to service) and `authorizations` (service to platform: Authorization approved, Authorization reversed, card on file), 3 partitions each, keyed by Card Token so one card's events arrive in order; each message names its event type in a header. Kafka's default 7-day retention; a lost service database is restored from backup, not replayed.
- **Platform outbox:** Spring Modulith's externalization (`spring-modulith-events-kafka`, `@Externalized`) sends events from the event publication registry they're stored in with the change, in order (`serialize-externalization`).
- **Service outbox:** the service writes each event to an `outbox` table in the same transaction as the change; a relay sends unpublished rows in insertion order and marks them sent. The extra insert rides the same commit, so the hot path gets no extra disk flush.
- **Repeats:** Kafka delivers at least once, so every consumer keys its write on the event's natural ID (request ID, or Card Token and event type) and does nothing the second time.
- **Format:** JSON. Each side keeps its own Java records, with no shared classes.
- **Contract tests:** Pact JVM message pacts. Each consumer writes a pact saying what it needs, the producer's test checks its real events against it, and the pact files live in the repo with no Pact Broker. Pact JVM 4.7.5 is tested up to Java 23, so the first ticket proves it on Java 25 and falls back to Spring Cloud Contract if it fails. The Network link has no contract test: both ends compile from one proto.

### The gaps (Q10, Q11, Q18)

Each side's copy trails the other's by Kafka's delay. Two places can't tolerate that:

- **A card not yet on file:** the saga issues the card, then waits in a new state until the service's "card on file" event arrives, and only then sends the Remittance Advice, so a Supplier never gets a card the service would decline as unknown (`14`). The wait has no limit: the card's 30-day expiry ends it if it never resolves. Grafana counts waiting Payments and alerts after 5 minutes.
- **A Clearing File ahead of the copy:** before matching, the import checks that the platform's consumer has no lag on `authorizations` (committed offset against end offset, through Kafka's admin client). If it lags, or Kafka is unreachable, the file stays in the drop folder and the next check retries it. This can't see events still in the service's outbox; the Kafka runbook says to check that first.

### The service's hot path (Q13, Q14, Q17)

- **Reactive:** the gRPC handler hands each call to Reactor, and the service reaches its database through R2DBC; WebFlux serves only its HTTP (actuator, health). Flyway runs its migrations over its own JDBC connection. Spring gRPC hosts any gRPC service on its Netty server beside WebFlux, but documents nothing reactive, so the bridge from the handler to Reactor is our code. The Kafka consumer and the outbox relay may block on their own threads: they're not on the hot path.
- **Expected result, written before measuring:** no gain on p99, because M4's miss came from the disk's commit flushes, which no thread model removes. M0 set blocking MVC on virtual threads as the baseline that M5 measures this against, and a measured "no gain" is a valid result.
- **Resilience4j** (`resilience4j-spring-boot4`): a circuit breaker and time limiter around the database on the hot path. When the database is slow or down, the service answers `91` (Issuer unavailable) in milliseconds instead of leaving the Network to time out, the same answer the bonus stand-in milestone will act on.
- **Security:** the service validates the Network's Keycloak token with `ROLE_network`, as `SecurityConfig` does today. Kafka is plaintext and unauthenticated on localhost; M7 brings mutual TLS.

### Observability and measurement (Q19, Q24)

- The service exports to the same `otel-lgtm` container under its own `service.name`. Spring Kafka's observation carries trace context in message headers; the service's outbox row stores the context it was written under and the relay restores it, so one trace runs from the Network's call through the service, Kafka and the platform's copy.
- Grafana: the Authorization dashboard and its p99 alert read the service; new panels for consumer lag on both topics and for Payments waiting for the card to be on file.
- Measurement: M4's simulation, unchanged, against the service; then one more run with both databases' data on a no-copy-on-write folder. Both go in a "Measured" section here.

## Deferred

Replaying the service's card copy from Kafka; deriving the security code from the PAN and expiry with a card-verification key (an M7 candidate); mutual TLS and Kafka authentication (M7); a Schema Registry; a Pact Broker; running both Authorization paths behind a switch; Network stand-in processing (bonus); an open-model load test, which needs Gatling Enterprise or another tool.

## Rejected options

- **Calling the platform for each Charge behind a circuit breaker:** a down platform would decline every Charge anyway, the failure the extraction exists to prevent.
- **Clearing calling the service per record:** 10,000 calls an import, and an import that fails whenever the service is down.
- **Moving all card status into the service:** `cards`, expiry, `suppliers`' link and `clearing` would all call it, dragging most of the monolith along.
- **Sharing the platform's database:** a batch job could still starve the hot path, and ADR-0009 meant extraction to move the schema.
- **Publishing after commit without an outbox:** a crash between commit and send loses an Authorization the Clearing then can't match.
- **Protobuf events shared through `contracts`:** drift fails at compile time, leaving contract tests nothing to check; Avro with a Schema Registry adds a container for the same effect.
- **Encrypting the security code into the event:** a secret on Kafka.
- **Cancelling a Payment that waits too long for its card to be on file:** a short Kafka outage would fail real Payments.
- **A strangler period with both paths answering:** one Network and no production traffic to protect.

## Risks

- **Pact on Java 25 is unproven:** the first ticket proves it before any contract depends on it, with Spring Cloud Contract as the fallback. Proven in #88: Pact JVM 4.7.5's consumer and provider message tests run on Java 25 (with Spring Boot's Kotlin 2.3.21), and a provider message with a wrong header fails verification, so the fallback wasn't needed.
- **The reactive bridge is hand-written:** a mistake there blocks a Netty thread and stalls every call; the service's tests run the bridge under concurrent calls, and the load test would show it.
- **A shared hash key:** the service now holds `vault.hash-key`, and with it a 3-digit security code's hash is trivially reversed; it's an environment variable like the vault's own keys, never logged, and M7 revisits it.
- **Two outboxes and a lag check:** the lag check can't see the service's unsent outbox rows, so a Clearing can still match against a copy missing an Authorization; the result is a Break a Reconciler sees, and the runbook says to drain the outbox first.
- **The measurement is still one laptop:** both databases share its disk, which the copy-on-write run is there to expose.
