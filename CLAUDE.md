# B2B Payments Platform

The Issuer's side of a B2B virtual-card platform, built as a learning and portfolio project. The owner designs and reviews; agents write the code. Business overview and milestone plan: `README.md`. Module map and diagrams: `docs/architecture.md`.

## Pull requests

PRs are the owner's main learning material, so every PR body has a **New concepts** section: for each concept, library or pattern the repo hasn't used before, 2–3 sentences on what it is and which failure it prevents. Write "None" when nothing is new.

The first PR that brings in a technology also adds `docs/learning/<topic>.md`: one page on the problem it solves here, its core mental model, and the gotchas we hit.

## Conventions

- **Architecture**: module boundaries, inter-module calls and events, and data access follow ADR-0003, ADR-0008 and ADR-0009. Payments run as the saga in ADR-0013.
- **Card data**: PANs live only in the `vault` module; every other module handles Card Tokens (ADR-0004).
- **Money**: integer minor units, always paired with a currency code.
- **APIs** (the platform's; the simulator is a local test driver): paths start with `/v1`; errors are RFC 9457 Problem Details; every POST that creates an Invoice or moves money requires an `Idempotency-Key` header, unless its own rule already makes repeats harmless (an Approver's approval counts once); lists page by cursor.
- **Tests**: test-first via the `tdd` skill. Domain rules get unit tests, each module gets Testcontainers integration tests, one end-to-end test runs the core path through the simulator, and the Spring Modulith verification test stays green.

## Docs

- `docs/design/<milestone>.md`: written and approved before the milestone's first code: problem, approach, rejected options, risks.
- `docs/architecture.md`: updated in the same PR that changes a module or its interactions.
- `README.md`: the milestone table's status updated in the PR that completes a milestone.
- `docs/runbooks/`: a failure plan, written when that failure first becomes possible (Kafka down, a Clearing File arriving twice).
- `docs/adr/`: decision records, per the domain docs below.

## Agent skills

### Issue tracker

Issues live in this repo's GitHub Issues, managed with the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
