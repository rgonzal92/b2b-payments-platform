-- One Credit Line per Buyer. buyer_id refers to buyers.buyer without a foreign key (ADR-0009).
create table credit_line (
    buyer_id     uuid primary key,
    amount_minor bigint not null check (amount_minor >= 0),
    currency     char(3) not null,
    updated_at   timestamptz not null default now()
);
