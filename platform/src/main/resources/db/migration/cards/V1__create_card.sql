-- Virtual Cards; in M1 each is a Single-Use Card for one Payment, locked to its Supplier and amount. The PAN lives only
-- in vault, under the same Card Token (ADR-0004). payment_id, buyer_id and supplier_id point into other modules'
-- schemas, so they carry no foreign keys (ADR-0009).
create table card (
    card_token   text primary key,
    -- One card per Payment, so issuing it again finds this row.
    payment_id   uuid not null unique,
    buyer_id     uuid not null,
    supplier_id  uuid not null,
    amount_minor bigint not null check (amount_minor > 0),
    currency     char(3) not null,
    last_four    char(4) not null,
    issued_at    timestamptz not null,
    expires_at   timestamptz not null,
    status       text not null
);
