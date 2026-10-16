-- One Remittance Advice per Payment, with its one-time link to the Single-Use Card's details. The ID is the Payment's;
-- invoice_id and card_token point into other modules' schemas, so they carry no foreign keys (ADR-0009).
create table remittance_advice (
    id              uuid primary key,
    invoice_id      uuid not null,
    invoice_number  text not null,
    supplier_id     uuid not null references supplier (id),
    amount_minor    bigint not null check (amount_minor > 0),
    currency        char(3) not null,
    card_token      text not null,
    -- SHA-256 of the link's token. The token itself is never stored: a copy of this table opens no card.
    link_token_hash bytea not null unique,
    link_expires_at timestamptz not null,
    -- Set by the one open that shows the card's details.
    link_used_at    timestamptz,
    created_at      timestamptz not null default now()
);
