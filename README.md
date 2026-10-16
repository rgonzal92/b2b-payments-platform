# B2B Payments Platform

Companies pay their suppliers by card: the platform issues a single-use virtual card for each approved invoice, approves the supplier's charge in real time, and reconciles every cent the next day. This repo builds the card issuer's side of that system.

It's a learning project modelled on a senior payments engineering role (virtual cards, payables, event-driven services). Every significant decision is recorded with its reasoning, so the docs matter as much as the code.

> **Status:** design complete, nothing built yet. See the [roadmap](#roadmap).

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

You need Java 25 (Corretto is what CI uses) and Docker running. Docker Compose starts the database and the telemetry stack for you.

1. Start the platform: `./mvnw -pl platform spring-boot:run`. The first start pulls PostgreSQL 18.6 and Grafana LGTM, then the platform listens on <http://localhost:8080>.
2. In a second terminal, start the simulator: `./mvnw -pl simulator spring-boot:run`. It listens on <http://localhost:8081> and reuses the running stack.
3. Open Grafana at <http://localhost:3000> (no login). Under **Explore**, pick **Tempo** for traces, **Loki** for logs or **Prometheus** for metrics. Metrics arrive once a minute.
4. Stopping an app leaves the stack running for the other. Stop the stack with `docker compose down`.

Tests need Docker but not the stack: `./mvnw verify` starts its own throwaway PostgreSQL.

## Roadmap

| # | What works at the end | Status |
|---|---|---|
| M0 | Project skeleton, local stack in Docker Compose, CI with secret scanning and SonarQube | Planned |
| M1 | Invoice → approvals → payment saga → single-use card in the vault → remittance link | Planned |
| M2 | The network simulator charges the card over gRPC; authorization rules, credit holds, reversals | Planned |
| M3 | The daily clearing file is processed by Spring Batch; daily settlement with the network | Planned |
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
