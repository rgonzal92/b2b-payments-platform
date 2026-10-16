-- buyer_id and supplier_id point into other modules' schemas, so they carry no foreign keys (ADR-0009).
create table invoice (
    id             uuid primary key,
    buyer_id       uuid not null,
    supplier_id    uuid not null,
    invoice_number text not null,
    amount_minor   bigint not null check (amount_minor > 0),
    currency       char(3) not null,
    due_date       date not null,
    status         text not null,
    received_at    timestamptz not null default now(),
    -- The duplicate rule: Invoice numbers are the Supplier's, so they repeat across Buyers.
    unique (buyer_id, supplier_id, invoice_number)
);
