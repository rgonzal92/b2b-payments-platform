-- The Payment saga's state row (ADR-0014): one Payment per approved Invoice. buyer_id points into another module's
-- schema, so it carries no foreign key (ADR-0009); the amount is the Invoice's, as InvoiceApproved carried it.
create table payment (
    id              uuid primary key,
    invoice_id      uuid not null unique references invoice (id),
    buyer_id        uuid not null,
    amount_minor    bigint not null check (amount_minor > 0),
    currency        char(3) not null,
    state           text not null,
    failure_reason  text,
    -- Failed attempts at the current step; the worker gives up after the fifth.
    attempts        integer not null default 0,
    -- When the worker should run the next step; null while no step is left to run.
    next_attempt_at timestamptz,
    created_at      timestamptz not null default now()
);

-- The worker claims only Payments with a step to run.
create index payment_next_attempt_at_idx on payment (next_attempt_at) where next_attempt_at is not null;
