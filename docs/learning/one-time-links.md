# One-time links and at-least-once delivery

## The problem it solves here

The Supplier needs the Single-Use Card's PAN, expiry and security code to charge it, and it has no login at the Issuer. The Remittance Advice therefore carries a link that shows the card's details to whoever opens it. That link is a credential as good as the card itself, and it travels over HTTP to an address the platform doesn't control. Delivering it is also unreliable: the POST can arrive and its answer get lost, so the platform can't know whether the Supplier has it and must be able to send it again.

## Mental model

- **A bearer link is a password in a URL.** Whoever holds it gets the card, so it gets everything a password gets and more: it can't be guessed (256 bits), it expires (7 days), it works once, no cache may keep its answer (`Cache-Control: no-store`), it is never logged, and it is stored only as a hash.
- **Store the hash, not the token.** Like a password, the token is stored as its SHA-256, so a copy of the database opens no card. A password needs a slow, salted hash (bcrypt, Argon2) because people choose guessable ones; a 256-bit token can't be guessed however fast the hash, so plain SHA-256 is enough.
- **Derive the token instead of drawing it.** A random token that is stored only as a hash can't be sent again: once the first attempt's answer is lost, the platform no longer knows the token it sent. Here the token is HMAC-SHA-256 of the Payment's ID under a key only the platform holds. Every attempt works out the same token, and without the key nobody can work out any Payment's token. The key becomes a secret like the vault's keys: whoever has it can compute the link of any Payment whose ID they know.
- **Use it up in the statement that reads it.** "Is it unused? Then mark it used" in two statements lets two opens both see "unused" and both get the card. One `update ... set link_used_at = now() where ... link_used_at is null returning ...` does the check and the change at once. Under READ COMMITTED the second update waits for the first one's row lock, then checks its `where` again against the committed row, finds it used, and changes nothing.
- **At least once, made safe by an idempotency key.** Over a network, "exactly once" can't be promised: a sender that never got an answer must either resend (maybe twice) or give up (maybe never). The platform resends, and puts the advice's ID in an `Idempotency-Key` header so the receiver can recognise a repeat and drop it. Because every copy carries the same link, it doesn't matter which one the Supplier keeps. This is the receiving side of [`idempotency-keys.md`](idempotency-keys.md): there the platform drops an ERP's repeats, here a Supplier drops the platform's.

## How it's built here

```text
payables worker (step 3, inside the Payment's transaction)
  └─ suppliers.RemittanceAdvices.deliver(advice, now)
       ├─ token = base64url(HMAC-SHA-256(remittance.link-key, payment id))
       ├─ new transaction, committed at once:
       │    insert into suppliers.remittance_advice (id = payment id, …,
       │           link_token_hash = sha256(token), link_expires_at = now + 7 days)
       │    on conflict (id) do nothing                ── a retry keeps the first record
       └─ POST <Supplier's remittance address>
            Idempotency-Key: <payment id>
            {id, invoiceId, invoiceNumber, amount, cardLink, cardLinkExpiresAt}
            2xx → Payment COMPLETED; anything else → retried, then card cancelled

Supplier: GET /v1/remittance-links/{token}             (no sign-in)
  no advice with link_token_hash = sha256(token)? 404; its card not ACTIVE or AUTHORIZED? 410
  update suppliers.remittance_advice set link_used_at = now()
   where link_token_hash = sha256(token) and link_used_at is null and link_expires_at > now()
  returning …                                          ── no row: 410
  vault.Vault.cardDetails(card token) → PAN, security code   (Cache-Control: no-store)
```

- `LinkTokens` holds the key and does the HMAC and the hashing with the JDK (`javax.crypto.Mac`, `MessageDigest`). The key comes from `REMITTANCE_LINK_KEY`; `shared.SecretKeys` refuses a missing or wrong-length key at startup, as it does the vault's.
- The record commits before the POST so that the link already works when the Supplier receives it. The rest of the step (the Payment's new state) still commits after the Supplier's answer, so a failed attempt leaves only that record behind, and the next attempt sends the same link with it.
- A Payment whose delivery fails for good cancels its card; a link to a cancelled card answers `410`, so a link the Supplier received before an answer was lost shows nothing.
- The simulator's Globex keeps each `Idempotency-Key` it has seen and answers a repeat with 200 without opening the link again.

## Gotchas we hit

- **A link that works only after the commit.** Recording the advice in the worker's transaction, which commits after the Supplier answers, would let a Supplier that opens the link while handling the POST (the simulator's Globex does) find nothing and get `404`. The advice commits in a transaction of its own (`REQUIRES_NEW`) before the POST.
- **Error messages carry URLs and bodies.** Spring's I/O errors quote the request URL, and its 4xx/5xx errors quote the response body. Globex's link carries the token, so Globex logs only the error's class or status; a Supplier's error answer might echo the advice, link included, so the platform's delivery error names only the status.
- **Generated models print every field.** The contract's `SingleUseCardDetails` comes with a `toString` that includes the PAN and security code. Nothing logs one; `vault.CardDetails` overrides its `toString` to show only the last four digits.
- **Traces record full URLs.** Every traced request records its URL (`http.url`), which for a link is the token. A test that records every observation while a link is opened caught it on both sides. The platform's `RequestObservations` records a link request by its route (`/v1/remittance-links/{token}`), and Globex opens links with a client that isn't traced.
- **Testing the delivery with `MockRestServiceServer`.** `@AutoConfigureMockRestServiceServer` binds a mock server to every `RestClient.Builder` Spring Boot hands out, so the delivery must build its client from the injected builder; setting its own request factory would replace the mock's. Its timeouts therefore come from `spring.http.clients.*`. The mock takes no new expectations once it has served a request (tests call `reset()` between deliveries), and a request nobody expected throws an `AssertionError`, which the worker doesn't catch, so a stray delivery fails the test loudly.
- **CSRF in the simulator.** Spring Security's CSRF protection refused the platform's POST to Globex with `403`: the POST has no session and no CSRF token. That path is exempt, since a request without cookies has no session for a forged cross-site request to ride on.
- **A race test has to make the race happen.** Two opens started together often run one after the other. The test makes both wait at a barrier just before opening; a version that read the link and then marked it in two statements (with a pause between them) failed it, both opens showing the card.
