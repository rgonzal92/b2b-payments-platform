-- Each Clearing File's Settlement: the Issuer pays the Network the file's net settlement amount, the Network's figure
-- from its trailer, and keeps beside it what the platform itself imported, for M4's Reconciliation. journal_entry_id
-- points into ledger's schema, so it carries no foreign key (ADR-0009).
create table settlement (
    file_id              text primary key references clearing_file (id),
    -- The trailer's net settlement amount: what the Network settles, and what the Settlement posted.
    amount_minor         bigint not null check (amount_minor >= 0),
    -- The sum of the file's posted Clearings.
    imported_total_minor bigint not null check (imported_total_minor >= 0),
    -- Of both amounts: the trailer's currency.
    currency             char(3) not null,
    -- False when the Network settles a figure other than the platform's: the Network Settlement Payable keeps the gap.
    matched              boolean generated always as (amount_minor = imported_total_minor) stored,
    -- The Journal Entry that posted it; null when the amount is zero, as an entry can't move nothing.
    journal_entry_id     uuid,
    settled_at           timestamptz not null default now(),
    constraint settlement_posted_check check ((amount_minor = 0) = (journal_entry_id is null))
);
