# Architecture

**Status:** planned; nothing is built yet. Terms follow [`GLOSSARY.md`](../GLOSSARY.md), and the reasoning behind each choice is in [`docs/adr/`](adr/). The milestone plan is in the [README](../README.md#roadmap).

## System context

The `platform` is the Issuer's system. Everything it talks to, apart from Keycloak, is played by the `simulator` app (ADR-0002).

```mermaid
flowchart LR
  buyerStaff["Buyer staff<br/>(Approvers)"]
  issuerStaff["Issuer staff<br/>(resolve Breaks)"]
  subgraph sim["simulator app"]
    erp["Buyer ERP"]
    supplier["Supplier"]
    acquirer["Acquirer"]
    network["Network"]
  end
  platform["platform<br/>(the Issuer's system)"]
  keycloak["Keycloak"]

  erp -- "Invoices (REST)" --> platform
  buyerStaff -- "approve Invoices (REST)" --> platform
  issuerStaff -- "resolve Breaks (REST)" --> platform
  platform -- "Remittance Advice link" --> supplier
  supplier -- "charges card" --> acquirer
  acquirer --> network
  network -- "Authorization, Reversal (gRPC)" --> platform
  network -- "daily Clearing File" --> platform
  platform -. "validates tokens" .-> keycloak
```

## Runtime (M0 to M4)

Docker Compose runs PostgreSQL (one schema per module) and `grafana/otel-lgtm` for metrics, traces and logs; Keycloak joins in M1. The `platform` and `simulator` run from Maven and start the stack themselves through Spring Boot's Docker Compose support.

## Modules

The `platform` is a modular monolith (ADR-0003). Spring Modulith tests enforce the boundaries.

| Module | Owns | Calls |
|---|---|---|
| `buyers` | Buyer profiles, Approval Thresholds | |
| `suppliers` | Suppliers, Supplier Relationships, Remittance Advice delivery | |
| `payables` | Invoices, approvals, Payments (runs the Payment saga) | `ledger`, `cards`, `suppliers` |
| `cards` | Virtual Card lifecycle, Card Tokens | `vault` |
| `vault` | PANs, encrypted (ADR-0004) | |
| `authorization` | Authorization rules; answers the Network | `cards`, `ledger` |
| `ledger` | Credit Lines, Holds, postings, Available Credit (ADR-0005) | |
| `clearing` | Clearing File processing, Settlement | `ledger` |
| `reconciliation` | Matching Charges to Authorizations and Invoices; Breaks | |

How modules interact:

- **Direct call** when the caller needs an answer now, such as `authorization` checking a card.
- **Event** when others react to something that happened. Events are stored with the change that raised them and redelivered until handled (ADR-0008).
- **Data**: each module owns its own schema; no joins or foreign keys cross modules (ADR-0009).

## Core payment path

```mermaid
sequenceDiagram
  participant ERP as ERP (sim)
  participant PAY as payables
  participant LED as ledger
  participant CRD as cards + vault
  participant SUP as suppliers
  participant SPL as Supplier (sim)
  participant NET as Network (sim)
  participant AUT as authorization
  participant CLR as clearing
  participant REC as reconciliation

  ERP->>PAY: POST /v1/invoices (Idempotency-Key)
  Note over PAY: Approvers approve the Invoice
  PAY->>LED: place Hold for the Invoice amount
  PAY->>CRD: issue Single-Use Card
  PAY->>SUP: send Remittance Advice
  SUP-->>SPL: one-time card link
  SPL->>NET: charge card (through the Acquirer)
  NET->>AUT: Authorization request (gRPC)
  AUT->>CRD: check card rules
  AUT-->>NET: approve or decline, 99% within 100 ms
  NET->>CLR: next day: Clearing File
  CLR->>LED: post the cleared amount
  CLR->>REC: cleared Charges
  Note over REC: anything unmatched becomes a Break
```

## Payment saga

`payables` orchestrates each Payment (ADR-0013). Every step is safe to repeat, and the Payment's state is saved after each one, so a crash resumes where it stopped.

| Step | Module | If it fails for good |
|---|---|---|
| 1. Place the Hold | `ledger` | The Payment fails; the Invoice stays approved and unpaid |
| 2. Issue the card and store its PAN | `cards`, `vault` | Release the Hold |
| 3. Send the Remittance Advice | `suppliers` | Cancel the card, release the Hold |

## Quality targets

- **Authorization latency:** 99% answered within 100 ms at the platform. The Network gives up after 1 second, declines, and sends a Reversal (ADR-0010).

## Planned evolution

Each extraction gets its own ADR naming the force behind it (ADR-0003).

- **M5:** `authorization` becomes its own service, driven by the latency target. Events start crossing process boundaries through Kafka.
- **M6:** everything runs on Kubernetes (k3d and Helm) behind Spring Cloud Gateway.
- **M7:** `vault` becomes its own service and database, for PCI isolation.
