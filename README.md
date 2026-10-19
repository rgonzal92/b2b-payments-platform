# B2B Payments Platform

Companies pay their suppliers by card: the platform issues a single-use virtual card for each approved invoice, approves the supplier's charge in real time, and reconciles every cent the next day. This repo builds the card issuer's side of that system.

It's a learning project modelled on a senior payments engineering role (virtual cards, payables, event-driven services). Every significant decision is recorded with its reasoning, so the docs matter as much as the code.

> **Status:** M4 (Reconciliation and measurement) is done: steps 1 to 6 below work end to end, reconciliation included. An ERP's invoice is approved by one or two approvers and paid with a single-use card whose full number lives only in the encrypted vault; the supplier opens the card's details once through its remittance advice link and charges the card, and the simulated card network asks over gRPC, and the authorization rules approve or decline it (since the M5 cutover, `authorization-service` answers, no longer the platform). If no answer comes within a second, the network declines the charge and sends a reversal, which cancels any authorization approved too late. The network's daily clearing file is imported by a Spring Batch job that posts every charge to a double-entry ledger, records how each matched its authorization, and pays the network the file's total; a broken or repeated file is refused whole, a repeated record is skipped, and an import that stops restarts where it stopped. What the buyer owes counts against its credit until the issuer records its repayment. A card nobody charges within 30 days, or whose charge doesn't clear within 7 days, expires and frees its credit. A daily reconciliation turns every mismatch (an undercharge, a clearing for a card nobody issued, an authorization that never cleared, an unpaid invoice, a settlement that disagrees with the network, books out of balance) into a Break, which a Reconciler resolves by accepting it or by posting a correcting entry that moves the money where it belongs; Issuer staff can read Breaks but not resolve them. Grafana shows four dashboards and alerts when the authorization p99 stays above 100 ms. A Gatling load test at 200 authorizations a second measured a server-side p99 of 35.6 ms on one run and 104.2 ms on the other, the miss caused by the laptop disk's commit flushes, not the platform's code ([measured](docs/design/m4-reconciliation-and-measurement.md#measured-75)). Once the apps are running, three commands run it, switches on the second show every clearing edge case, and one more resolves a Break (see [Run it locally](#run-it-locally)). Next is M5: Authorization becomes its own service. See the [roadmap](#roadmap).

## How a payment works

Acme (a Buyer) owes Globex (a Supplier) $12,400.

1. Acme's ERP sends Globex's invoice to the platform.
2. An Acme approver approves it. Invoices above Acme's approval threshold need two different approvers.
3. The platform reserves $12,400 of Acme's credit and issues a single-use virtual card locked to Globex and that amount.
4. Globex receives a remittance advice with a one-time link to the card details.
5. Globex charges the card. The card network asks `authorization-service`, the Issuer's own authorization service, to approve the charge, and it answers within 100 ms.
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

You need Java 25 (Corretto), Docker running, OpenSSL, and ports 5432, 5433, 3000, 4317, 4318, 8080, 8081, 8082, 8180 and 9090 free. Docker Compose starts the database, the telemetry stack and Keycloak for you.

1. Once, generate local secrets: `./scripts/init-env.sh`. It writes random development secrets into `.env`, which git ignores; running it again keeps existing values and adds any new ones. After pulling a change to `keycloak/payments-realm.json` (such as a new client), run it again, then `docker compose up -d --force-recreate keycloak`: Keycloak imports the realm only into a fresh container. If your `.env` predates the card vault or the Remittance Advice, run it again too: it adds the vault's two keys (`VAULT_ENCRYPTION_KEY`, `VAULT_HASH_KEY`) and the Remittance Advice link key (`REMITTANCE_LINK_KEY`), without which the platform refuses to start. M2 adds the Network's Keycloak client: run it again for `NETWORK_CLIENT_SECRET`, then recreate Keycloak as above. M4 adds the `simulator-reconciler` client, which resolves Breaks: run it again for `SIMULATOR_RECONCILER_SECRET`, then recreate Keycloak.
2. Start the platform: `./mvnw -pl platform -am spring-boot:run`. The first start pulls the PostgreSQL, Grafana LGTM and Keycloak images, then the platform listens on <http://localhost:8080> for REST. Then, in another terminal, start M5's `authorization-service` beside it: `./mvnw -pl authorization-service -am spring-boot:run`. Either may start first. The service answers the Network's gRPC calls on `127.0.0.1:9090`, which need the `network` client's token, from its own copy of the cards, kept from the platform's `cards` topic in its own PostgreSQL; it reports its health on <http://localhost:8082/actuator/health>. The platform keeps a copy of the service's approved and reversed Authorizations, from the `authorizations` topic, for Clearing and Reconciliation. Payments need the service: each one waits, its card issued, until the service says it has the card on file, and only then sends the Supplier its Remittance Advice; without the service, a Payment stays `CARD_ISSUED` ([`docs/runbooks/payment-stuck.md`](docs/runbooks/payment-stuck.md)), and the Network declines every Charge itself with `91`.

   Coming from an earlier milestone? Reset local data first with `docker compose down -v`: the platform's Authorizations from before the cutover and the service's copy of the cards would disagree.
3. In another terminal, start the simulator: `./mvnw -pl simulator -am spring-boot:run`. It listens on <http://localhost:8081>, reuses the running stack, and creates the Buyers Acme (the demo's) and Initech (the load test's) through the platform, signed in through Keycloak. Its card network reaches the service's gRPC server on `localhost:9090`, signed in as the `network` client with `NETWORK_CLIENT_SECRET` from `.env` (step 1).
4. Run the whole payment in one call: `curl -s -X POST http://localhost:8081/demo/invoices`. The simulator's ERP submits a $12,400 invoice from Globex, two of Acme's approvers approve it (it is above Acme's $10,000 threshold), and the simulator waits for the platform to pay it. Globex then charges the card: its acquirer sends the charge through the simulator's card network, which asks `authorization-service` over gRPC. Within a second or two the demo answers with the invoice, who approved it, `"paymentState": "COMPLETED"`, what Globex received through its one-time link (the card's last four digits, expiry and amount, never the full number) and the service's answer to the charge: `"authorization": {"approved": true, "responseCode": "00", "authorizationCode": "…"}`. If the service doesn't answer within a second, or can't be reached, the network declines the charge itself with `"responseCode": "91"` and reverses it; see [`docs/runbooks/network-cant-reach-platform.md`](docs/runbooks/network-cant-reach-platform.md). Add `?minorUnits=500000` for a $5,000 invoice, which takes one approval. Each paid invoice takes its amount of Acme's $100,000 credit line, reserved until its charge clears and then owed until Acme repays it (Issuer staff record that with `POST /v1/buyers/{buyerId}/repayments`), so the ninth $12,400 demo answers `"paymentState": "FAILED"` with `"Not enough Available Credit to place the Hold."`. `docker compose down` followed by restarting the apps starts again with an empty database. For any other payment that isn't `COMPLETED`, see [`docs/runbooks/payment-stuck.md`](docs/runbooks/payment-stuck.md).
5. Clear the charges: `curl -s -X POST localhost:8081/demo/clearing-file`. Globex's acquirer submitted each approved charge for clearing; now the simulator's card network writes today's clearing file with every one it hasn't sent yet into `var/clearing`, the platform's drop folder, and the simulator, signed in as Issuer staff, asks the platform to import it at once. The answer is the file's name and the platform's answer to the import, its summary, such as `{"fileName": "NET-20261016-7K2QX9A.txt", "imports": [{"status": 201, "summary": {"id": "NET-20261016-7K2QX9A", "sequenceNumber": 1, "status": "IMPORTED", "recordCount": 1, "outcomes": {"matched": 1, …}, "settlement": {…, "matched": true}}}]}`; with nothing to clear it writes no file and answers `204` with no body. Without the call, the network writes the file for each day at 00:05 UTC and the platform's 30-second check of the folder imports it. The network keeps the charges it hasn't sent, and its count of files sent, in memory only: restarting the simulator loses them, and its next file is number 1 again.

   To watch each of the platform's clearing rules at work, charge a card again (step 4), then add switches, which change only that call's file: `duplicateRecord` sends a record twice (the platform skips the repeat, `"skippedCount": 1`, and records the settlement as mismatched, as the network settles the record twice); `aboveAuthorizedAmount` clears the charge $100 above what the platform authorized; `noAuthorization` adds a clearing on the card that no authorization matches; `unknownPan` adds one for a card the platform never issued, which it posts to Clearing Suspense; `wrongTrailerTotal` puts the file's control total a cent out, so the platform refuses the whole file (`"status": 422`); and `sameFileTwice` sends the file twice, so the second import answers `409` (or `422` again beside `wrongTrailerTotal`: a refused file doesn't count as imported). Each import's outcome counts or problem show in `imports`. Combine switches with `&`: `curl -s -X POST 'localhost:8081/demo/clearing-file?duplicateRecord=true&noAuthorization=true&unknownPan=true'`. A refused or repeated file waits in `var/clearing/failed/` and holds the cards' full numbers: delete it once you've seen it ([`docs/runbooks/clearing-file-refused.md`](docs/runbooks/clearing-file-refused.md)). The network doesn't send a refused file's charges again, so their credit stays reserved until the 7-day rule frees it.
6. Reconcile today: `curl -s -X POST localhost:8081/demo/reconciliation`. The simulator, signed in as Issuer staff, asks the platform to run today's Reconciliation at once and lists today's open Breaks. The answer is the run's summary and each Break's ID, kind, gap and Buyer (`null` when none is known), such as `{"run": {"runId": "…", "businessDate": "2026-10-16", "breaksFound": 1, "newBreaks": 1, "knownBreaks": 0}, "openBreaks": [{"id": "…", "kind": "UNKNOWN_CARD", "gap": {"minorUnits": 1240000, "currency": "USD"}, "buyerId": null}]}`. After a plain step 5 it finds nothing to raise: `"breaksFound": 0` and no open Breaks. Running it again raises nothing twice: a Break it found before counts in `knownBreaks`. Without the call, the platform reconciles each day at 00:05 UTC the next day; if a run fails, see [`docs/runbooks/reconciliation-run-failed.md`](docs/runbooks/reconciliation-run-failed.md).

   To resolve a Break, charge a card (step 4), clear it with `curl -s -X POST 'localhost:8081/demo/clearing-file?unknownPan=true'`, and reconcile (above): the platform posted the unknown card's clearing to Clearing Suspense, and the answer shows its `UNKNOWN_CARD` Break with no Buyer. Say Acme turns out to owe it. As a Reconciler, the `simulator-reconciler` client signed in with `SIMULATOR_RECONCILER_SECRET` from `.env`, reassign it to Acme (Buyer `dc9a9857-e40f-4d50-9ee3-879f0c7472c5`) for the Break's gap. From the repository root, with `jq` installed, put the Break's `id` from the answer in `break_id`, and if its `gap` isn't the default charge's 1240000, put its `minorUnits` in `amount` instead:

   ```sh
   break_id=<the UNKNOWN_CARD Break's id>
   secret=$(grep '^SIMULATOR_RECONCILER_SECRET=' .env | cut -d= -f2-)
   token=$(curl -s -d grant_type=client_credentials -d client_id=simulator-reconciler \
     --data-urlencode "client_secret=$secret" \
     http://localhost:8180/realms/payments/protocol/openid-connect/token | jq -r .access_token)
   curl -s -X POST http://localhost:8080/v1/breaks/$break_id/resolution \
     -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
     -d '{"kind": "REASSIGNED", "buyerId": "dc9a9857-e40f-4d50-9ee3-879f0c7472c5",
          "amount": {"minorUnits": 1240000, "currency": "USD"},
          "note": "Acme confirmed the charge on the unknown card"}'
   ```

   The answer is the Break, `"status": "RESOLVED"`, with a `resolution` naming the Reconciler, the note and the `correctingEntryId`: a Correcting Entry debited Acme's Buyer Receivable and credited Clearing Suspense, so Acme now owes the amount. `curl -s http://localhost:8080/v1/breaks/$break_id -H "Authorization: Bearer $token"` shows it `RESOLVED` too, with the facts of the clearing it names; `GET /v1/breaks?status=OPEN` lists the rest. Resolving it again answers `409`, so money moves once. A Break is also resolved as `ACCEPTED` (a note, no money moves) or `CORRECTED` (a balanced Correcting Entry between any two Accounts); see `POST /v1/breaks/{breakId}/resolution` in [the API](contracts/src/main/resources/openapi/platform-api.yaml) and [how Reconciliation works](docs/learning/reconciliation.md). Issuer staff can read Breaks but not resolve them.
7. Open Grafana at <http://localhost:3000> (no login). Under **Dashboards > B2B Payments**, **Authorization** shows the Network's calls, their p50/p95/p99 latency against the 100 ms target and the answers by response code; **Payments** the saga states Payments reach and how long each step runs; **Clearing** the files imported or refused, Clearings by outcome and Settlements matched or mismatched; **Breaks** the open Breaks by kind and by age. **Alerting > Alert rules** holds the one rule, *Authorization p99 above 100 ms*, which fires after 5 minutes above it and notifies no one; [the load test](#the-load-test) is how to put that p99 under load. They come from `grafana/` in this repository ([how to edit them](docs/learning/grafana-provisioning.md)). Under **Explore**, pick **Tempo** for traces, **Loki** for logs or **Prometheus** for metrics. Metrics arrive once a minute. In Tempo, the TraceQL query `{name="http post /demo/invoices"}` finds each demo call as one trace across the simulator and the platform.
8. Keycloak's admin console is at <http://localhost:8180>: user `admin`, password `KEYCLOAK_ADMIN_PASSWORD` from `.env`.
9. Stopping an app leaves the stack running for the other. Stop the stack with `docker compose down`. If only part of it is running, run `docker compose up -d` before starting an app: Spring Boot starts the stack only when none of it is running.

### Cards for the load test

The load test charges fresh cards that the platform issued through its real path. With the platform, `authorization-service` and the simulator running (steps 2 and 3), seed them with `curl -s -X POST 'localhost:8081/demo/load-test-cards?count=1000'` (`count` from 1 to 25,000). Initech's ERP sends that many $10.00 invoices to Globex, Initech's approver approves each (Initech's threshold is $100, so one approval is enough), the simulator waits for each payment, and Globex opens each card's one-time link. The answer is `{"requested": 1000, "written": 1000, "file": "…/var/load-test/cards.csv"}`; `written` is lower if some payments failed or took over two minutes, and the simulator's log says why. Add `&append=true` to add the cards to the file instead of overwriting it. The simulator keeps 64 invoices in flight, and the platform's payment worker runs every due payment's steps each second, on `payables.payment-worker.threads` workers (4), each payment pausing until `authorization-service` has its card on file. The seeding rate hasn't been measured since M5 added that wait. Initech's $1,000,000 credit line covers 100,000 such cards before Issuer staff need to record a repayment.

`var/load-test/cards.csv` holds one card per line under the header `pan,securityCode,expiry,amountMinorUnits,currency,acceptorId`, the expiry as `YYMM`. Git ignores it, but it holds the cards' full numbers and security codes: delete it after the load test.

### The load test

The `load-test` module's Gatling simulation measures the platform's answer to the Network's `Authorize` against the 100 ms target (ADR-0010). CI never runs it: it compiles there, but a p99 on a shared runner means nothing.

1. Start the stack and both apps (steps 1 to 3), with nothing else busy on the machine: a build or test run beside it moves the p99.
2. Seed 28,000 fresh cards, the most one call takes being 25,000: `curl -s -X POST 'localhost:8081/demo/load-test-cards?count=25000'`, then `curl -s -X POST 'localhost:8081/demo/load-test-cards?count=3000&append=true'`. The run charges about 27,300 of them, each fresh card once, so seed again before every run.
3. Run it, signed in as the Network with the secret from `.env`: `NETWORK_CLIENT_SECRET=$(grep '^NETWORK_CLIENT_SECRET=' .env | cut -d= -f2-) ./mvnw -B -pl load-test -am -Pload-test verify`. It sends 200 Authorizations a second for a 30-second warm-up, then for 2 measured minutes, from 5 users that each start a Charge every 25 ms: Gatling's open-source gRPC support stops a run past 5 users ([why](docs/learning/gatling.md)), so a slow answer lowers the achieved rate as well as showing as latency. About 90% charge a fresh card and expect `00`; 5% charge a card already charged (`57`) and 5% send a wrong security code (`N7`). Any other answer or a gRPC error fails the call, and one failed call fails the build. `-Drate=20 -DwarmUpSeconds=5 -DmeasuredSeconds=10` makes a short trial run; `-DcardsFile=…` charges another file.
4. Read the results. Gatling's client-side numbers are in the console and in the HTML report under `load-test/target/gatling/`: the three **Measured: …** rows are the 2-minute window, apart from the **Warm-up: …** rows; check their requests per second add up to about 200. The platform's own server-side p50/p95/p99 are on Grafana's **Authorization** dashboard; set the time range to the measured window. The 100 ms target is the server-side p99. The M4 design records each measured run.
5. Delete the cards file: `rm var/load-test/cards.csv`.

How to read the report and what tripped us up: [`docs/learning/gatling.md`](docs/learning/gatling.md).

Tests need Docker but not the stack or `.env`: `./mvnw verify` starts its own throwaway PostgreSQL, Kafka and Keycloak. Among them, the end-to-end test starts the platform and the simulator in one JVM and `authorization-service` beside them from its executable jar, then runs the demo and a small load-test seeding through them; `./mvnw -B -pl e2e -am verify` builds and tests the three apps, then runs it.

## Roadmap

| # | What works at the end | Status |
|---|---|---|
| M0 | Project skeleton, local stack in Docker Compose, CI with secret scanning and SonarQube | Done |
| M1 | Invoice → approvals → payment saga → single-use card in the vault → remittance link | Done |
| M2 | The network simulator charges the card over gRPC; authorization rules, credit holds, reversals | Done |
| M3 | The daily clearing file is processed by Spring Batch; daily settlement with the network | Done |
| M4 | Reconciliation and Breaks; load test against the 100 ms target; Grafana dashboards | Done |
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
