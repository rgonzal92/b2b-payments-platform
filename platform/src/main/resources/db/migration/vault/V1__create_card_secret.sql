-- The only table holding PANs (ADR-0004), keyed by the Card Token every other module uses instead. Each ciphertext is
-- AES-256-GCM: the 12-byte nonce, then the encrypted value and its 16-byte tag, with the Card Token as associated data.
create table card_secret (
    card_token               text primary key,
    -- HMAC-SHA-256 of the PAN under the hash key: finds a PAN without decrypting, and keeps each PAN on one card.
    pan_hash                 bytea not null unique,
    pan_ciphertext           bytea not null,
    security_code_ciphertext bytea not null,
    created_at               timestamptz not null default now()
);
