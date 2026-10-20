# M7: The Card Vault becomes its own service

**Status:** approved 2026-10-20. Decisions from the M7 grilling (Rounds 1–5, Q1–Q29, 2026-10-20).

## Problem

The Card Vault is a module of the platform, so the whole platform is in PCI scope. Its `vault.card_secret` table sits in the platform's database beside every other module's, its two keys (`VAULT_ENCRYPTION_KEY`, `VAULT_HASH_KEY`) sit in the platform's environment, and three modules handle a decrypted PAN in memory: `suppliers` decrypts and returns the PAN and security code over HTTP when a Supplier opens its Remittance Advice link, and `clearing` hands every Clearing File record's PAN to the vault (`Vault.cardFor`, in chunks of 100) to find its Card Token, while the Network's Clearing File rests in the platform's drop folder with PANs in clear text. Nothing between the apps is encrypted in transit. A real Issuer's assessor would have to examine every line of the platform, because any of it could reach a PAN.

The force is PCI scope reduction (ADR-0018, Q1). M7 moves the Card Vault into its own app, `vault-service`, with its own database, puts its encryption key in OpenBao behind envelope encryption, and adds mutual TLS on every hop that carries a PAN or the Card Vault's keys, so that the platform holds no PAN at rest or in memory (Q2). Amended ADR-0004 lists every place a PAN may still appear, and why.

## Done when

1. A PAN sweep test finds no PAN in any platform table or in the platform's logs after the end-to-end test has run, and runs after the platform's `vault` schema is dropped (Q18, Q20).
2. The end-to-end test runs the core path through `vault-service`: a Single-Use Card is issued, Globex opens its Remittance Advice link and follows the redirect to its Card Details, the Network's Charge is approved, and the Clearing File arrives at the platform tokenised and imports as before (Q18).
3. A test proves the copy job moves the platform's `vault.card_secret` into `vault-service`'s database, re-encrypted, and that running it again changes nothing (Q12, Q18).
4. A test rotates the key-encryption key in OpenBao, runs the rewrap job, and reveals a card issued before the rotation (Q10, Q18).
5. CI's `k3d` job runs with mutual TLS on, seeds its cards by revealing each card's Card Details from `vault-service` directly, past the Gateway (Q25), and rolls `vault-service` too, alongside the other apps, with 0 failed calls (Q18).
6. On each mutual TLS hop (see Mutual TLS), a client without a valid certificate, or speaking plaintext, is refused (Q11, Q18).
7. The Authorization p99 stays under 100 ms (Q18): CI's k3d run ends with 0 failed calls and the existing 100 ms p99 alert stays quiet; M7 records no local "Measured" run (Q27).
8. A Payment waiting for the Card Vault raises the "Payment waiting for the Card Vault" alert after 5 minutes, and a Clearing File `vault-service` can't read lands in `var/clearing/network-rejected/` and raises an alert (Q23, Q28).
9. The last M7 ticket drops the platform's `vault` schema, removes `link_used_at`, and takes `VAULT_ENCRYPTION_KEY` out of the platform (Q20, Q24, Q28).
10. ADR-0018, ADR-0004's amendment listing every place a PAN may still appear and why (Q1), `docs/learning/openbao.md` (Q3), runbooks `vault-service-down`, `openbao-sealed` and `kek-rotation` (Q5, Q10, Q19), and `docs/architecture.md`'s module map and deployment view are written (Q22).

## Approach

### What moves where (Q1, Q2, Q4)

| What | Today | After M7 |
|---|---|---|
| PANs and security codes, encrypted | `vault.card_secret` in the platform's database | `vault-service`'s own PostgreSQL |
| Generating a PAN | `Vault.generatePan`, in `cards`' transaction | `vault-service`'s `IssueCard`, which also stores the card's expiry (see Issuing a card) |
| Revealing Card Details to a Supplier | `suppliers` decrypts and returns them | `vault-service`, behind a redirect (see Revealing Card Details) |
| A Clearing File record's PAN to Card Token | `clearing` calls `Vault.cardFor` | `vault-service` tokenises the file before the platform sees it (see The Clearing File) |
| The encryption key | `VAULT_ENCRYPTION_KEY` in the platform's environment | A per-card data key, wrapped by a key-encryption key in OpenBao Transit |
| The hash key | `VAULT_HASH_KEY`, platform and `authorization-service` | `CARD_HASH_KEY`, `vault-service` and `authorization-service` only |

- **`vault-service`** is a new Maven module, built as `authorization-service` is: Spring MVC on virtual threads, JDBC, its gRPC API in `contracts/` (Q4). The platform calls it over gRPC; the Card Details route is HTTP.
- **Its own database:** a PostgreSQL of its own, `vault-postgres` on port 5434 in Compose and a StatefulSet in the chart (Q4).
- **Two pods** in the chart, as the other serving apps have since M6 (Q5).
- **The platform's `vault` module goes away** once the cutover is done (see Moving today's secrets). `cards` calls `vault-service`; `suppliers` and `clearing` no longer touch a PAN.

### Issuing a card (Q6)

Today `SingleUseCards.issueSingleUse` writes `cards.card` and `vault.card_secret` in one transaction. Across two databases that transaction is gone, so the step is ordered to be safe to repeat:

1. The Payment saga's row records a fresh Card Token first, in its own transaction.
2. The saga worker calls `vault-service`'s `IssueCard(cardToken, expiry)`, and `vault-service` stores the expiry with the card (Q26). It is idempotent by Card Token: the first call generates the PAN and security code and stores them; a repeat returns the same card. It returns the last four digits and the PAN's and security code's keyed hashes, never the PAN.
3. The worker commits `cards.card` and `CardIssued` (with the hashes, as today) in one transaction.

A crash between 2 and 3 is retried with the same Card Token and gets the same card. A Card Vault that is unavailable or misses its deadline makes the step wait, not fail: it is retried every 10 seconds with no attempt count, as M5's wait for the card to be on file is; any other error counts toward the step's 5 attempts (`MAX_ATTEMPTS`) as today (Q23). A card secret whose Card Token never reaches `cards.card`, because the Payment stops before a retry commits, stays in `vault-service` as an orphan: see Risks. No reaper is built (Deferred).

### Revealing Card Details (Q7, Q13, Q14)

The Supplier still opens one link, but the platform no longer returns the Card Details:

1. `GET /v1/remittance-links/{token}` in `suppliers` checks the link: it exists, hasn't expired, and its card is `ACTIVE` or `AUTHORIZED`. It no longer uses the link up (Q24).
2. It answers `303` to `/v1/card-details/{grant}`. The grant names the Card Token, lives 60 seconds, and is signed by the platform with Ed25519 (Q13).
3. The Gateway routes `/v1/card-details/*` to `vault-service`, which checks the grant's signature and age with the platform's public key, decrypts the card and returns its Card Details.

- **"Revealed once"** is `vault-service`'s `revealed_at` on the card, the only "once": the first reveal sets it, and any later one answers `410 Gone`. `link_used_at` goes in Q20's cleanup ticket (Q24).
- **The Card Details** are the PAN, security code and expiry month and year, all from `vault-service`, with no call back to the platform (Q26).
- **Keys:** `scripts/init-env.sh` generates the Ed25519 key pair; the platform holds the private key, `vault-service` the public key; in the chart they are Secrets (Q13).
- **Rate limit:** `/v1/card-details/*` shares the remittance link's per-IP bucket at the Gateway (a burst of 10, then one every 6 seconds), so an opening costs two requests and a client gets 5 openings a minute (Q14).
- **The Gateway** sees the Card Details in transit, so it is in PCI scope as transit-only: it stores and logs nothing of the body (Q7).
- **The simulator's Globex** follows the redirect (Q7). Under `load-test-values.yaml`, the simulator instead fetches Card Details from `vault-service` directly, over mutual TLS with its own client certificate, skipping the Gateway as M6's seeding skipped it for the platform; the Gateway's limit is unchanged (Q25).

### The Clearing File (Q8)

- **`vault-service` owns the Network's drop folder:** the Network writes its Clearing File to `var/clearing/network-inbox/`, and `vault-service` writes a tokenised copy, atomically, into the platform's current drop folder, then deletes the original (Q28). In the copy, each record's PAN is replaced by its Card Token, or, for a PAN no card has, an empty Card Token and the last four digits.
- **`clearing` imports the copy** unchanged otherwise: matching, Settlement and every Break work as today, and an unknown card takes the existing path.
- **A file `vault-service` can't read** moves to `var/clearing/network-rejected/`, PANs and all, and an alert fires (Q28). The platform's own `failed/` then holds only tokenised copies.

### Keys: envelope encryption and the hash key (Q3, Q9, Q10, Q15, Q16)

- **Envelope encryption:** each card gets its own data key from OpenBao Transit's `datakey` endpoint. `vault-service` encrypts the PAN and security code under it with AES-256-GCM, Card Token as associated data as today, and stores the wrapped data key beside the ciphertext. Issue and reveal each cost one Transit call (`datakey`, `decrypt`) (Q10).
- **The key-encryption key** lives only in OpenBao Transit (Q3). Rotating it is a Transit `rotate`, then a rewrap job that rewraps each stored data key under the newest version with Transit's `rewrap`, never seeing a plaintext key; the `kek-rotation` runbook covers it (Q10). Rotation is by hand, following the runbook: `bao` rotates the key, then the rewrap runs as a one-off Job or script (Q28).
- **OpenBao in Compose:** dev mode, in memory, its dev root token in `.env`, the Transit engine and key created at start. The README warns that restarting it loses every card (Q15).
- **OpenBao in the cluster:** a single StatefulSet with file storage. `scripts/k8s-openbao-init.sh` initialises it and stores the unseal key in a Secret; the chart says "not production" (Q15).
- **Authentication:** Kubernetes auth (the pod's ServiceAccount token) in the cluster, a token from `.env` in Compose. `vault-service`'s policy allows only `datakey`, `decrypt` and `rewrap` on its one Transit key (Q16).
- **The hash key** stays a plain shared secret, renamed `CARD_HASH_KEY`, held by `vault-service` and `authorization-service` only, not fetched from OpenBao: a Transit call per Charge would not fit ADR-0010's p99. `authorization-service` is therefore in PCI scope: it hashes the Network's PAN in memory (Q9).

### Mutual TLS (Q11, Q17)

| Hop | Carries |
|---|---|
| platform → `vault-service` | `IssueCard` |
| Gateway → `vault-service` | Card Details |
| simulator → `vault-service`, under `load-test-values.yaml` only | Card Details, for seeding (Q25) |
| Network (simulator) → `authorization-service` | The Charge's PAN and security code |
| `vault-service` → OpenBao | Data keys |
| `vault-service` → its PostgreSQL | Ciphertext and wrapped data keys |

- **Certificates in the cluster:** cert-manager with a private CA; certificates live 24 hours and renew at 16. Spring Boot's SSL bundles reload on update, gRPC included, so a renewal needs no restart (Q11, Q17).
- **Certificates in Compose:** `scripts/init-certs.sh` makes a CA and certificates with `openssl`, valid for a year (Q11, Q17).
- **Alert:** a certificate under 2 hours from expiry (Q17).
- **Not yet:** Kafka, the platform's database, and every other HTTP hop stay plaintext (Deferred).

### Moving today's secrets (Q12, Q20)

As M5 cut Authorization over (#92, PR #104):

1. An idempotent copy job, a one-off `vault-service` command that a cutover script runs (Q28), reads the platform's `vault.card_secret`, decrypts each card under today's key and re-encrypts it into `vault-service`'s database under a fresh data key, keyed by the same Card Token and PAN hash. Running it again copies only what is missing.
2. The cutover switches `cards` to `IssueCard`, `suppliers` to the redirect and `clearing` to the tokenised copy; the copy job runs again to catch cards issued before the switch.
3. A later release, M7's last ticket, drops the platform's `vault` schema; the PAN sweep runs after it (Q20).

`VAULT_HASH_KEY` becomes `CARD_HASH_KEY`, and `VAULT_ENCRYPTION_KEY` leaves the platform in the schema-drop ticket (Q28). The copy job reads the platform's database as a temporary read-only role, allowed `SELECT` on `vault.card_secret` only, which the cutover script creates, hands the job as a short-lived Secret and drops when the job ends, so `vault-service` keeps no platform credential (Q29).

### When the Card Vault is down (Q5)

- **Issuing:** the saga worker retries the issue step every 10 seconds with no attempt count, so Payments wait rather than fail, and "Payment waiting for the Card Vault" fires after 5 minutes (Q23).
- **Revealing:** `/v1/card-details` answers `503`; the link was never used up and `revealed_at` isn't set, so the Supplier opens it again later (Q24).
- **Authorization** is unaffected: `authorization-service` answers from its Card Copy and its own hash key.
- **Clearing:** the Network's file waits in `var/clearing/network-inbox/`, and the import retries once a tokenised copy arrives.
- Two `vault-service` pods; the `vault-service-down` runbook covers the rest.

### Observability (Q19)

- A Card Vault dashboard: Transit latency, issue and reveal rates, and reveals refused, by reason.
- An app-down alert for `vault-service`, per job as M6's are, and an alert on Transit errors.
- "Payment waiting for the Card Vault": a Payment has waited more than 5 minutes for the issue step (Q23).
- A Clearing File moved to `var/clearing/network-rejected/` (Q28).
- Runbooks: `vault-service-down`, `openbao-sealed`, `kek-rotation`.

### Proving it (Q18)

- **The PAN sweep** searches every platform table and the platform's logs for a Luhn-valid PAN after the end-to-end test, once the `vault` schema is gone (Q20).
- **The end-to-end test** issues a card, has Globex follow the redirect to its Card Details, charges it, and imports the tokenised Clearing File.
- **The copy job and KEK rotation** each get a Testcontainers test, OpenBao included.
- **CI's `k3d` job** installs the chart with mutual TLS, seeds the load test's cards through the new Card Details route, straight to `vault-service` under `load-test-values.yaml` (Q25), and rolls `vault-service` as well as the other apps; 0 failed calls.
- **Each mutual TLS hop** gets a test that a plaintext client, or one without a valid certificate, is refused.
- **Latency:** no local "Measured" run in M7; CI's 0 failed calls and the existing 100 ms p99 alert are the check (Q27).

## Deferred

Mutual TLS on Kafka, the platform's database and the other HTTP hops; OpenBao auto-unseal with an HSM or cloud KMS, and OpenBao highly available; a reaper for orphan card secrets; deriving the security code from the PAN and expiry with a card-verification key (ADR-0004's M7 candidate, not decided); Sealed Secrets.

## Rejected options

- **Keeping the reveal in `suppliers`:** the platform would keep handling the PAN in memory and stay in scope (Q7).
- **`clearing` calling `vault-service` per record:** the Clearing File would still bring PANs into the platform (Q8).
- **Hashing through OpenBao:** a Transit call per Charge doesn't fit ADR-0010's p99 (Q9).
- **One static encryption key in the environment, as today:** whoever reads the environment reads every card (Q3, Q10).
- **Counting Card Vault downtime as attempts:** an outage longer than 5 attempts would fail Payments that only had to wait (Q23).
- **Using the link up on opening:** a reveal that failed, with the Card Vault down, would leave the Supplier with a dead link (Q24).
- **Seeding through the Gateway:** at 5 openings a minute per IP, CI's 9,000 cards would take 30 hours, and the limit stays as it is (Q25).
- **A local "Measured" run:** rejected for CI's 0 failed calls and the existing 100 ms p99 alert as the check (Q27).
- **The Card Vault staying a module:** the whole platform stays in PCI scope (Q1).

## Risks

- **Orphan card secrets:** a card secret whose Payment stops before `cards.card` commits stays in `vault-service`, encrypted, with no card pointing to it and no reaper (Q6).
- **OpenBao dev mode in Compose keeps everything in memory:** restarting it loses the Transit key, and with it every card in `vault-service`'s database. Fine for development; the README warns.
- **The Gateway sees PANs in transit:** it is in scope as transit-only, and a log or trace of a response body there would leak Card Details. It must log nothing of `/v1/card-details` bodies.
- **The unseal key in a Kubernetes Secret:** anyone who can read the Secret can unseal OpenBao. Acceptable for a learning cluster marked "not production"; auto-unseal is deferred.
- **24-hour certificates that fail to renew** stop every mutual TLS hop within hours; the under-2-hours alert gives time to act.
- **A reveal grant replayed within its 60 seconds** reveals nothing: `revealed_at` is already set after the first reveal, so it gets `410`.
- **Card Details outside the Gateway under `load-test-values.yaml`:** the simulator's direct path skips the rate limit; it exists only on a load-test install, and mutual TLS still requires the simulator's certificate (Q25).
- **A rejected Clearing File rests with PANs** in `var/clearing/network-rejected/` until someone deletes it; the alert makes sure someone looks (Q28).
- **Mutual TLS adds the Network's hop a handshake** with no M7 measurement of its own: a p99 regression would show only through the existing 100 ms alert (Q27).
- **A long Card Vault outage holds Payments indefinitely:** with no attempt count they never fail on their own; the 5-minute alert is what brings a person in (Q23).
