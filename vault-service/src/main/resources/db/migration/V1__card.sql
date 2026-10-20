-- The Card Vault's cards (M7, ADR-0004 as amended), keyed by the Card Token every other app uses instead of the PAN.
-- Each ciphertext is AES-256-GCM under the card's own data key: the 12-byte nonce, then the encrypted value and its
-- 16-byte tag, with the Card Token as associated data. The data key is stored only wrapped by OpenBao Transit's
-- key-encryption key, as Transit's ciphertext "vault:v<key version>:<base64>".
create table card (
    card_token               text        primary key,
    -- HMAC-SHA-256 of the PAN under CARD_HASH_KEY: keeps each PAN on one card without decrypting anything.
    pan_hash                 bytea       not null unique,
    pan_ciphertext           bytea       not null,
    security_code_ciphertext bytea       not null,
    wrapped_data_key         text        not null,
    expires_at               timestamptz not null,
    created_at               timestamptz not null default now()
);
