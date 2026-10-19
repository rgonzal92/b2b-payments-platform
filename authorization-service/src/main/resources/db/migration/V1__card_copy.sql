-- The service's copy of every card it may authorize, fed by the platform's card events (M5).
-- No PAN or security code: only their keyed hashes under vault.hash-key, base64-encoded (ADR-0004).
create table card_copy (
    card_token         text        primary key,
    pan_hash           text        not null unique,
    security_code_hash text        not null,
    expires_at         timestamptz not null,
    amount_minor       bigint      not null check (amount_minor > 0),
    currency           char(3)     not null,
    acceptor_id        text,
    status             text        not null check (status in ('ACTIVE', 'CANCELLED', 'EXPIRED'))
);
