# A vault key is missing or wrong

**Signals:** the platform stops at startup with `vault.encryption-key is missing` (or `vault.hash-key`), `… isn't valid base64` or `… must be 32 bytes (256 bits) once base64-decoded`.

The vault encrypts each card's PAN and security code under one key and hashes each PAN under another (ADR-0004, [`docs/learning/card-vault.md`](../learning/card-vault.md)). It refuses to start with a key it can't trust rather than issue cards under a weak or missing one, so nothing in the platform runs until both keys are right. The keys come from `VAULT_ENCRYPTION_KEY` and `VAULT_HASH_KEY` in `.env`; an environment variable of the same name overrides the file.

## Local development

1. Run `./scripts/init-env.sh`. It adds the two keys to `.env` when they are missing (a `.env` from before the card vault lacks them), and never changes existing values.
2. If a key is present but refused, look for a hand edit of `.env` or an environment variable of the same name. Each must be 32 random bytes, base64-encoded: `openssl rand -base64 32`.
3. Replacing a key that was already used makes the cards stored under it unreadable (encryption key) or impossible to find by PAN (hash key). In local development that only loses demo cards; drop them with the rest of the database (`docker compose down -v`) if they get in the way.

## The Remittance Advice link key

`remittance.link-key is missing` (or `isn't valid base64`, `must be 32 bytes`) is the same failure for the key that works out each Remittance Advice's link token ([`docs/learning/one-time-links.md`](../learning/one-time-links.md)), from `REMITTANCE_LINK_KEY` in `.env`. The fix is the same: run `./scripts/init-env.sh`. Links already sent keep working when it is replaced, since only their hashes are looked up; a delivery still being retried would send a new link that doesn't open.

## Every Charge is declined `14`

**Signals:** every `Authorize` answers `14` (invalid card), even for a card issued minutes ago; new rows in `"authorization".authorizations` all have a null `card_token`; the platform started without complaint.

The vault finds the card for a Charge by the PAN's HMAC under the hash key, never by decrypting ([`docs/learning/card-vault.md`](../learning/card-vault.md)). A different but valid `VAULT_HASH_KEY` passes the startup check, yet no stored hash matches what it computes, so every PAN looks unknown and every Charge gets `14`. Cards issued after the change are found; cards issued before are not.

1. Confirm it: recent answers by response code, and whether those PANs' cards exist.

   ```sql
   select response_code, count(*), count(card_token) as with_card
     from "authorization".authorizations
    where created_at > now() - interval '1 hour'
    group by response_code;
   select status, count(*) from cards.card group by status;
   ```

   All `14` with no card, while `cards.card` holds `ACTIVE` cards that should have been charged, points at the hash key.
2. Look for what changed `VAULT_HASH_KEY`: a hand edit of `.env`, or an environment variable of the same name overriding it. Restore the key the cards were issued under and restart the platform; the next Charge finds its card.
3. If that key is lost, the stored hashes can't be recomputed without decrypting every PAN under the encryption key and hashing it again under the new key; nothing does that yet (M7 replaces the keys with envelope encryption). In local development, drop the demo cards with the database (`docker compose down -v`).
