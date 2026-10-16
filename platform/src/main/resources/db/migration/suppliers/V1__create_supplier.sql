-- One Supplier per real business, shared by every Buyer that invoices it (ADR-0012).
create table supplier (
    id                 uuid primary key,
    tax_id             text not null unique,
    name               text not null,
    remittance_address text not null,
    created_at         timestamptz not null default now()
);

-- A Buyer's own link to a Supplier.
create table supplier_relationship (
    buyer_id        uuid not null,
    supplier_id     uuid not null references supplier (id),
    buyer_reference text not null,
    created_at      timestamptz not null default now(),
    primary key (buyer_id, supplier_id)
);
