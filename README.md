# B2B Payments Platform

Companies pay their suppliers by card: the platform issues a single-use virtual card for each approved invoice, approves the supplier's charge in real time, and reconciles every cent the next day. This repo builds the card issuer's side of that system.

It's a learning project modelled on a senior payments engineering role (virtual cards, payables, event-driven services). Every significant decision is recorded with its reasoning, so the docs matter as much as the code.

> **Status:** M3 (Clearing and Settlement) is done: steps 1 to 6 below work end to end, all but reconciliation. An ERP's invoice is approved by one or two approvers and paid with a single-use card whose full number lives only in the encrypted vault; the supplier opens the card's details once through its remittance advice link and charges the card, and the simulated card network asks the platform over gRPC, which approves or declines by its authorization rules. If the platform doesn't answer within a second, the network declines the charge and sends a reversal, which cancels any authorization the platform approves too late. The network's daily clearing file is imported by a Spring Batch job that posts every charge to a double-entry ledger, records how each matched its authorization, and pays the network the file's total; a broken or repeated file is refused whole, a repeated record is skipped, and an import that stops restarts where it stopped. What the buyer owes counts against its credit until the issuer records its repayment. A card nobody charges within 30 days, or whose charge doesn't clear within 7 days, expires and frees its credit. Once the apps are running, two commands run it, and switches on the second show every clearing edge case (see [Run it locally](#run-it-locally)); the 100 ms target is measured in M4. Next is M4: reconciliation and Breaks. See the [roadmap](#roadmap).

## How a payment works

Acme (a Buyer) owes Globex (a Supplier) $12,400.

1. Acme's ERP sends Globex's invoice to the platform.
2. An Acme approver approves it. Invoices above Acme's approval threshold need two different approvers.
3. The platform reserves $12,400 of Acme's credit and issues a single-use virtual card locked to Globex and that amount.
4. Globex receives a remittance advice with a one-time link to the card details.
5. Globex charges the card. The card network asks the platform to approve the charge, and the platform answers within 100 ms.
6. The next day, the network's clearing file confirms the final amount, the platform settles with the network, and reconciliation flags anything that doesn't match for a person to resolve.

The card network, Globex's bank and Acme's ERP are simulators. The platform is the real system.

## What makes it hard

- **Money must add up.** A double-entry ledger records every movement, and a retried request never charges twice.
- **Real-time decisions.** 99% of card approvals are answered within 100 ms.
- **Payments that fail halfway.** Paying an invoice spans several steps; a saga undoes the earlier ones when a later one fails.
- **Card data handled as if PCI DSS applied.** Full card numbers live in one encrypted vault; everything else handles tokens.
- **Systems that disagree.** The network's records and ours are reconciled daily, and every mismatch becomes a Break for a person to resolve.

## Tech

Java 25, Spring Boot 4, Maven, PostgreSQL, Spring Modulith, gRPC, Spring Batch, Keycloak, and OpenTelemetry with Grafana. Later milestones add WebFlux, Kafka, Resilience4j, Kubernetes (k3d and Helm), Spring Cloud Gateway, Redis and Spring AI. Each technology arrives in the milestone where the system first needs it, and the [decision records](docs/adr/) say why.

## Run it locally

You need Java 25 (Corretto), Docker running, OpenSSL, and ports 5432, 3000, 4317, 4318, 8080, 8081, 8180 and 9090 free. Docker Compose starts the database, the telemetry stack and Keycloak for you.

1. Once, generate local secrets: `./scripts/init-env.sh`. It writes random development secrets into `.env`, which git ignores; running it again keeps existing values and adds any new ones. After pulling a change to `keycloak/payments-realm.json` (such as a new client), run it again, then `docker compose up -d --force-recreate keycloak`: Keycloak imports the realm only into a fresh container. If your `.env` predates the card vault or the Remittance Advice, run it again too: it adds the vault's two keys (`VAULT_ENCRYPTION_KEY`, `VAULT_HASH_KEY`) and the Remittance Advice link key (`REMITTANCE_LINK_KEY`), without which the platform refuses to start. M2 adds the Network's Keycloak client: run it again for `NETWORK_CLIENT_SECRET`, then recreate Keycloak as above.
2. Start the platform: `./mvnw -pl platform -am spring-boot:run`. The first start pulls the PostgreSQL, Grafana LGTM and Keycloak images, then the platform listens on <http://localhost:8080> for REST and on `127.0.0.1:9090` for the Network's gRPC calls, which need the `network` client's token.
3. In a second terminal, start the simulator: `./mvnw -pl simulator -am spring-boot:run`. It listens on <http://localhost:8081>, reuses the running stack, and creates the Buyers Acme (the demo's) and Initech (the load test's) through the platform, signed in through Keycloak. Its card network reaches the platform's gRPC server on `localhost:9090`, signed in as the `network` client with `NETWORK_CLIENT_SECRET` from `.env` (step 1).
4. Run the whole payment in one call: `curl -s -X POST http://localhost:8081/demo/invoices`. The simulator's ERP submits a $12,400 invoice from Globex, two of Acme's approvers approve it (it is above Acme's $10,000 threshold), and the simulator waits for the platform to pay it. Globex then charges the card: its acquirer sends the charge through the simulator's card network, which asks the platform over gRPC. Within a second or two the demo answers with the invoice, who approved it, `"paymentState": "COMPLETED"`, what Globex received through its one-time link (the card's last four digits, expiry and amount, never the full number) and the platform's answer to the charge: `"authorization": {"approved": true, "responseCode": "00", "authorizationCode": "…"}`. If the platform doesn't answer within a second, or can't be reached, the network declines the charge itself with `"responseCode": "91"` and reverses it; see [`docs/runbooks/network-cant-reach-platform.md`](docs/runbooks/network-cant-reach-platform.md). Add `?minorUnits=500000` for a $5,000 invoice, which takes one approval. Each paid invoice takes its amount of Acme's $100,000 credit line, reserved until its charge clears and then owed until Acme repays it (Issuer staff record that with `POST /v1/buyers/{buyerId}/repayments`), so the ninth $12,400 demo answers `"paymentState": "FAILED"` with `"Not enough Available Credit to place the Hold."`. `docker compose down` followed by restarting both apps starts again with an empty database. For any other payment that isn't `COMPLETED`, see [`docs/runbooks/payment-stuck.md`](docs/runbooks/payment-stuck.md).
5. Clear the charges: `curl -s -X POST localhost:8081/demo/clearing-file`. Globex's acquirer submitted each approved charge for clearing; now the simulator's card network writes today's clearing file with every one it hasn't sent yet into `var/clearing`, the platform's drop folder, and the simulator, signed in as Issuer staff, asks the platform to import it at once. The answer is the file's name and the platform's answer to the import, its summary, such as `{"fileName": "NET-20261016-7K2QX9A.txt", "imports": [{"status": 201, "summary": {"id": "NET-20261016-7K2QX9A", "sequenceNumber": 1, "status": "IMPORTED", "recordCount": 1, "outcomes": {"matched": 1, …}, "settlement": {…, "matched": true}}}]}`; with nothing to clear it writes no file and answers `204` with no body. Without the call, the network writes the file for each day at 00:05 UTC and the platform's 30-second check of the folder imports it. The network keeps the charges it hasn't sent, and its count of files sent, in memory only: restarting the simulator loses them, and its next file is number 1 again.

   To watch each of the platform's clearing rules at work, charge a card again (step 4), then add switches, which change only that call's file: `duplicateRecord` sends a record twice (the platform skips the repeat, `"skippedCount": 1`, and records the settlement as mismatched, as the network settles the record twice); `aboveAuthorizedAmount` clears the charge $100 above what the platform authorized; `noAuthorization` adds a clearing on the card that no authorization matches; `unknownPan` adds one for a card the platform never issued, which it posts to Clearing Suspense; `wrongTrailerTotal` puts the file's control total a cent out, so the platform refuses the whole file (`"status": 422`); and `sameFileTwice` sends the file twice, so the second import answers `409` (or `422` again beside `wrongTrailerTotal`: a refused file doesn't count as imported). Each import's outcome counts or problem show in `imports`. Combine switches with `&`: `curl -s -X POST 'localhost:8081/demo/clearing-file?duplicateRecord=true&noAuthorization=true&unknownPan=true'`. A refused or repeated file waits in `var/clearing/failed/` and holds the cards' full numbers: delete it once you've seen it ([`docs/runbooks/clearing-file-refused.md`](docs/runbooks/clearing-file-refused.md)). The network doesn't send a refused file's charges again, so their credit stays reserved until the 7-day rule frees it.
6. Open Grafana at <http://localhost:3000> (no login). Under **Explore**, pick **Tempo** for traces, **Loki** for logs or **Prometheus** for metrics. Metrics arrive once a minute. In Tempo, the TraceQL query `{name="http post /demo/invoices"}` finds each demo call as one trace across the simulator and the platform.
7. Keycloak's admin console is at <http://localhost:8180>: user `admin`, password `KEYCLOAK_ADMIN_PASSWORD` from `.env`.
8. Stopping an app leaves the stack running for the other. Stop the stack with `docker compose down`. If only part of it is running, run `docker compose up -d` before starting an app: Spring Boot starts the stack only when none of it is running.

### Cards for the load test

The load test charges fresh cards that the platform issued through its real path. With both apps running (steps 2 and 3), seed them with `curl -s -X POST 'localhost:8081/demo/load-test-cards?count=1000'` (`count` from 1 to 10,000). Initech's ERP sends that many $10.00 invoices to Globex, Initech's approver approves each (Initech's threshold is $100, so one approval is enough), the simulator waits for each payment, and Globex opens each card's one-time link. The answer is `{"requested": 1000, "written": 1000, "file": "…/var/load-test/cards.csv"}`; `written` is lower if some payments failed or took over two minutes, and the simulator's log says why. The platform's payment worker runs about three payments a second, so expect about six minutes per 1,000 cards and close to an hour for 10,000 (an estimate from the worker's pace, not yet timed on a laptop). Initech's $1,000,000 credit line covers 100,000 such cards before Issuer staff need to record a repayment.

`var/load-test/cards.csv` holds one card per line under the header `pan,securityCode,expiry,amountMinorUnits,currency,acceptorId`, the expiry as `YYMM`. Git ignores it, and each run overwrites it, but it holds the cards' full numbers and security codes: delete it after the load test.

Tests need Docker but not the stack or `.env`: `./mvnw verify` starts its own throwaway PostgreSQL and Keycloak. Among them, the end-to-end test starts both apps in one JVM and runs the demo through them; `./mvnw -B -pl e2e -am verify` builds and tests the two apps, then runs it.

## Roadmap

| # | What works at the end | Status |
|---|---|---|
| M0 | Project skeleton, local stack in Docker Compose, CI with secret scanning and SonarQube | Done |
| M1 | Invoice → approvals → payment saga → single-use card in the vault → remittance link | Done |
| M2 | The network simulator charges the card over gRPC; authorization rules, credit holds, reversals | Done |
| M3 | The daily clearing file is processed by Spring Batch; daily settlement with the network | Done |
| M4 | Reconciliation and Breaks; load test against the 100 ms target; Grafana dashboards | Planned |
| M5 | Authorization becomes its own service (WebFlux, Kafka, Resilience4j, contract tests) | Planned |
| M6 | Everything runs on Kubernetes (k3d, Helm) behind Spring Cloud Gateway with rate limiting | Planned |
| M7 | The card vault becomes its own service and database; envelope encryption; mutual TLS | Planned |
| M8 | GenAI assistant that drafts a likely cause for each Break, with measured accuracy | Planned |
| M9 | Angular UI for approvers and Break resolution | Planned |

Bonus, in order: ISO 8583 over TCP with Vert.x, network stand-in processing, JMS settlement instructions, then multi-use cards, multiple currencies, supplier acceptance and statements.

## Docs

- [Architecture](docs/architecture.md): modules, diagrams, the core payment path
- [Decision records](docs/adr/): every significant choice and its trade-off
- [Glossary](GLOSSARY.md): the domain vocabulary, one word per concept

## How it's built

GenAI-first: I design each milestone and review every pull request; AI coding agents write the code from tickets. Every pull request explains the concepts it introduces, and `docs/learning/` collects a one-page explainer for each new technology.
