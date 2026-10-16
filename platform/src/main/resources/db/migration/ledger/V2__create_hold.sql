-- Part of a Buyer's Available Credit reserved for a Virtual Card (ADR-0011). The module placing the Hold chooses
-- its ID (a Payment's ID), so placing the same Hold again finds this row instead of adding another.
create table hold (
    id           uuid primary key,
    buyer_id     uuid not null references credit_line (buyer_id),
    amount_minor bigint not null check (amount_minor > 0),
    currency     char(3) not null,
    placed_at    timestamptz not null default now()
);

create index hold_buyer_id_idx on hold (buyer_id);
