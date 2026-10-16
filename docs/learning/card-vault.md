# The card vault: tokenization, AES-GCM and a keyed hash

## The problem it solves here

Each Payment issues a Single-Use Card, and a card's PAN and security code are what a fraudster needs to spend the Buyer's credit. PCI DSS makes every system that stores, processes or sees a PAN part of the audited "cardholder data environment", so a real Issuer keeps PANs in as few places as possible (ADR-0004). Here the PANs are synthetic, but the platform is built as if they weren't: only the `vault` module ever holds a PAN, encrypted, and everything else (`cards`, `payables`, the Invoice API, the logs) handles a Card Token.

## Mental model

- **Tokenization: a random stand-in, not a disguised PAN.** The Card Token is 16 random bytes from `SecureRandom`, base64url-encoded. It isn't computed from the PAN, so there is nothing to reverse: the link between a token and its PAN exists only as a row in `vault.card_secret`. Leak every table outside the vault and no PAN leaks with them.
- **Last four digits are safe to show.** PCI DSS lets a masked PAN show at most its first six and last four digits, so `cards` keeps the last four for people to recognise the card by. `Vault.generatePan` returns those four digits and nothing else.
- **AES-GCM is authenticated encryption.** It encrypts (nobody without the key can read the PAN) and authenticates (any change to the stored bytes makes decryption fail instead of returning a wrong PAN). The output is the ciphertext plus a 16-byte authentication tag computed over it.
- **A nonce must never repeat under the same key.** GCM needs a 12-byte (96-bit) nonce per encryption. Two messages encrypted under one key and one nonce reveal the XOR of their plaintexts, and let an attacker forge tags. The vault draws a fresh random nonce for every encryption and stores it in front of the ciphertext; the nonce isn't secret, it only has to be unique. Random nonces are safe for up to about 2³² encryptions per key, far beyond this platform.
- **Associated data binds a ciphertext to its row.** GCM can authenticate extra bytes that aren't encrypted. The vault passes the Card Token, so a PAN's ciphertext decrypts only under its own Card Token: an attacker who can write to the table can't copy one card's ciphertext into another card's row.
- **A keyed hash finds a PAN without decrypting.** Encryption is randomised (a new nonce each time), so the same PAN never encrypts to the same bytes, and a unique constraint or a lookup can't work on ciphertexts. A hash is deterministic, but a plain SHA-256 of a PAN is worthless: there are only a billion PANs in the `990000` range, and a laptop hashes them all in minutes. HMAC-SHA-256 mixes in a secret key, so nobody without the key can test guesses. The vault stores it beside the ciphertexts with a unique constraint (one PAN per card), and M2's Authorization will look cards up by it.
- **One key per purpose.** The encryption key and the hash key are separate 256-bit keys. If one leaks, the other still protects what it protects, and each can be replaced on its own schedule. Replacing the hash key means rehashing every PAN (decrypting each first); replacing the encryption key means re-encrypting each. Until then, a lost or changed key makes stored cards unreadable or unfindable, which is why `scripts/init-env.sh` never overwrites a key in `.env`.

## How it's built here

```text
payables (worker transaction)
  └─ cards.Cards.issueSingleUse(paymentId, …)      card exists for the Payment? return it
       ├─ Card Token = base64url(16 random bytes)
       ├─ vault.Vault.generatePan(cardToken)         ── only the last four digits come back
       │    ├─ security code = 3 random digits
       │    └─ repeat: PAN = 990000 + 9 random digits + Luhn check digit
       │         insert into vault.card_secret
       │           (card_token, hmac(hashKey, PAN),
       │            nonce ‖ AES-GCM(encKey, PAN, aad = card token),
       │            nonce ‖ AES-GCM(encKey, security code, aad = card token))
       │         on conflict (pan_hash) do nothing    ── no row: PAN taken, draw again
       └─ insert into cards.card (card token, Payment, Buyer, Supplier, amount, last four, expiry)
```

- `VaultCrypto` does the cryptography with the JDK's `javax.crypto` (`AES/GCM/NoPadding`, `HmacSHA256`): no extra library. A `Cipher` and a `Mac` aren't thread-safe, so each call gets its own.
- The keys come from `vault.encryption-key` and `vault.hash-key`, mapped from `VAULT_ENCRYPTION_KEY` and `VAULT_HASH_KEY` in `.env`. `VaultCrypto` is built at startup and throws when a key is missing, isn't base64 or isn't exactly 32 bytes, so the platform fails fast with a message naming the property, never its value.
- Everything joins the worker's transaction, so the card, its PAN and the Payment's new state commit together. A crash mid-step leaves no PAN behind for a card that doesn't exist.
- `ModularityTests.onlyCardsUsesTheVault` fails the build if any module but `cards` depends on `vault`.

## Gotchas we hit

- **The JDK accepts a short key without complaint.** `new SecretKeySpec(key, "AES")` with 16 bytes quietly gives AES-128, and HMAC takes a key of any length. The vault checks for exactly 32 bytes itself, and a test proves a short key is refused.
- **Error messages can leak a key.** `Base64.getDecoder()` reports an illegal character by quoting it, so the vault's "isn't valid base64" error doesn't chain the decoder's exception.
- **A unique violation aborts the whole transaction.** In PostgreSQL, a failed statement leaves its transaction unusable, so catching a duplicate-PAN error and retrying would fail on the next statement, and the worker would roll back the Hold's step too. `insert … on conflict (pan_hash) do nothing` reports the collision as "no row inserted" instead.
- **PostgreSQL rounds timestamps to microseconds.** The first test compared the card's expiry with the worker's `now` plus 30 days and was off by one microsecond: PostgreSQL rounds rather than truncates. `cards` now truncates `now` to microseconds itself, so the card returned at issuance equals the one read back later.
- **A test properties file can hide the real one.** `src/test/resources/application.properties` would replace the main file on the test classpath. The fixed test-only keys live in `src/test/resources/config/application.properties`, which Spring Boot loads on top of the main file. Every byte of each test key is the same (`0x01`, `0x02`), so they're obviously fake and gitleaks' entropy check ignores them.
- **"Only cards may use the vault" isn't an allowed-dependency rule.** Spring Modulith's `allowedDependencies` restricts what a module may depend on, not who may depend on it, so the rule is a test over Modulith's module model instead.
- **A test proves the vault joins the caller's transaction.** If `vault` committed on its own (`REQUIRES_NEW`), a crash after the PAN was stored but before the card was would leave an orphaned PAN. `PaymentSagaTests` stops the platform mid-step and checks no PAN is left without its card; with `REQUIRES_NEW` it failed.
