# The Authorization message

## The problem it solves here

When Globex charges its Single-Use Card, its Acquirer passes the Charge to the Network, and the Network asks the Issuer, within a second, whether to approve it. Every card network asks in ISO 8583: a request made of numbered *data elements* (DE), and an answer whose *response code* (DE 39) approves the Charge or says why not. The platform speaks gRPC to the simulator's Network (ADR-0006), but names every field after its data element, so the move to real ISO 8583 is a mapping. The Authorization rules (Q19) decide which code to answer, and the Network can send a request again without the Issuer approving it twice.

## Mental model

- **A message is a set of data elements.** Each one has a number and a fixed meaning that every network and Acquirer agrees on. The ones used here:

  | DE | Field in `issuer_authorization.proto` | What it's for |
  |---|---|---|
  | 2 | `primary_account_number` | The PAN: which card. Goes to the vault only (ADR-0004). |
  | 4, 49 | `amount_minor`, `currency_code` | How much, in which currency (real ISO 8583 carries the numeric ISO 4217 code). |
  | 7, 11 | `transmitted_at`, `system_trace_audit_number` | When the Network sent it, and its own trace number for the message. |
  | 14 | `expiration_date` | The card's expiry as printed, `YYMM`: proof the Supplier has the card, not just its number. |
  | 18 | `merchant_category_code` | The Supplier's kind of business. Not checked yet. |
  | 37 | `retrieval_reference_number` | The request ID: names this Authorization in a resend, a Reversal and, later, Clearing. |
  | 42, 43 | `card_acceptor_id`, `card_acceptor_name` | Which Supplier is charging (the Acceptor ID), and its name as the Acquirer knows it. |
  | (private) | `security_code` | The three digits on the card; real networks carry it in a private-use element. Goes to the vault only. |
  | 38, 39 | `authorization_code`, `response_code` | The answer: `00` and a six-character code on an approval, a decline code otherwise. |

- **Each decline code says what to do next.** The Acquirer shows the code to the Supplier, so it must name the real problem:

  | Code | Meaning | Why it exists here |
  |---|---|---|
  | `00` | Approved | The Charge passed every rule; the card becomes `AUTHORIZED`. |
  | `13` | Invalid amount | A zero or negative amount: no real Charge looks like that, so the message is wrong, not the card. |
  | `14` | Invalid card number | No card the Issuer issued has this PAN: a typo, or someone guessing numbers. |
  | `54` | Expired card | The card is cancelled or expired, or the expiry in the message isn't the card's or has passed. |
  | `N7` | Security code mismatch | The code isn't the card's: whoever charges may have the number but not the card. |
  | `57` | Not permitted | The card can't be used for this Charge: a Single-Use Card already has an approved Charge. |
  | `61` | Exceeds amount | More than the card's amount, or another currency: a Single-Use Card pays one Invoice, no more. |

- **The first rule that fails names the decline.** The rules run in the M2 design's order: the amount's form, the card, its state and expiry, the security code, the Supplier (#32), the amount, an earlier approval. An expired card with a wrong security code answers `54`; an `AUTHORIZED` card isn't expired, so a second Charge on it reaches the last rule and answers `57`. `AuthorizationRules` is that decision alone: facts in, response code out, no I/O, so each rule has plain unit tests.
- **A decline is an answer; a malformed request isn't.** A decline is a successful gRPC call with a code, recorded like an approval. A request without a retrieval reference number is refused with `INVALID_ARGUMENT` and recorded nowhere: there is no request ID to record it under or to answer a resend with.
- **The same request ID gets the same answer.** The Network resends when an answer doesn't arrive in time. The resend must get the first answer, whatever the rules would say now: the first approval made the card `AUTHORIZED`, so working the answer out again would say `57`, and the Network would decline a Charge the Issuer approved.

## How it's built here

```text
IssuerAuthorizationService.authorize         no retrieval reference number: INVALID_ARGUMENT
  └─ Authorizations.authorize                one transaction
       ├─ vault.cardFor(PAN)                  the Card Token, through the PAN's keyed hash
       ├─ cards.find(Card Token)              status, expiry, amount and currency
       ├─ vault.securityCodeMatches(…)        decrypts the stored code, compares in constant time
       ├─ AuthorizationRules.decide           the response code
       ├─ insert … on conflict (request_id) do nothing
       │    no row inserted: return the stored answer, change nothing
       └─ approved: cards.authorize           ACTIVE → AUTHORIZED; card no longer ACTIVE: record 57
```

- **The primary key settles a resend, not a read first.** The insert claims the request ID, the same pattern as `Idempotency-Key` ([`idempotency-keys.md`](idempotency-keys.md)). A resend that arrives while the first request is still running waits on the insert until the first commits, then inserts nothing and reads the stored answer, so only one of them ever moves the card.
- **Two Charges with different request IDs** can both read the card `ACTIVE`. The conditional update lets exactly one move it; the other finds it `AUTHORIZED` and its recorded answer becomes `57`.
- **The vault keeps the security code.** `securityCodeMatches` decrypts the stored code inside the vault and compares it with `MessageDigest.isEqual`, which takes as long whichever byte differs, so neither the code nor how much of a guess was right leaves the vault. Nothing records the request's expiry or security code.

## Gotchas we hit

- **A currency code that isn't ISO 4217 failed `UNKNOWN`, with nothing recorded.** `Currency.getInstance` threw before any rule ran, and the record's `char(3)` column would have refused a longer code anyway. The request's currency is now compared as sent and stored as `text`, so `DOLLARS` answers `61` and is recorded.
- **A repeated request ID failed `UNKNOWN`.** The plain insert hit the primary key and PostgreSQL aborted the transaction; `on conflict do nothing` reports it as "no row" instead.
- **Every caller must now send the expiry.** `CorePathTests` charged with the PAN and security code only, and the expiry rule declined it `54`; the simulator's Network has to send DE 14 too.
- **`MessageDigest.isEqual` is constant-time only for equal lengths.** It returns early when the lengths differ, which leaks a length the Network already knows: three digits.
- **google-java-format can't parse a lambda parameter named `sealed`.** It's a contextual keyword since Java 17; Spotless failed until the parameter was renamed.
