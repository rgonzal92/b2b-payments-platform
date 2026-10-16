-- The double-entry journal (ADR-0015). An Account's balance is the sum of its Journal Entries' lines, never a counter
-- kept beside them. buyer_id refers to buyers.buyer without a foreign key (ADR-0009).
create table account (
    id       uuid primary key,
    kind     text not null
             check (kind in ('BUYER_RECEIVABLE', 'NETWORK_SETTLEMENT_PAYABLE', 'ISSUER_CASH', 'CLEARING_SUSPENSE')),
    -- Only a Buyer Receivable belongs to a Buyer; the other kinds are one each per currency.
    buyer_id uuid,
    currency char(3) not null,
    constraint account_buyer_check check ((kind = 'BUYER_RECEIVABLE') = (buyer_id is not null)),
    -- Created on first use: posting finds the Account by these three, or adds it.
    constraint account_unique unique nulls not distinct (kind, buyer_id, currency)
);

-- A balanced record of money moving between Accounts. The idempotency key (a Clearing's Network record ID) is unique
-- per kind of entry, so posting the same one again finds this row instead of posting twice.
create table journal_entry (
    id              uuid primary key,
    kind            text not null check (kind in ('CLEARING')),
    idempotency_key text not null,
    posted_at       timestamptz not null default now(),
    constraint journal_entry_idempotency_key_unique unique (kind, idempotency_key)
);

-- One side of an entry: an amount debited or credited to one Account, in that Account's currency. An entry's debits
-- and credits are equal; the platform refuses any other entry before it reaches this table.
create table journal_line (
    entry_id     uuid not null references journal_entry (id),
    line_number  int not null,
    account_id   uuid not null references account (id),
    side         text not null check (side in ('DEBIT', 'CREDIT')),
    amount_minor bigint not null check (amount_minor > 0),
    primary key (entry_id, line_number)
);

create index journal_line_account_id_idx on journal_line (account_id);

-- Entries are never changed or deleted: a mistake is corrected by a new entry, so the books keep their history.
create function refuse_journal_change() returns trigger language plpgsql as $$
begin
    raise exception 'Journal Entries are never changed or deleted (ADR-0015): post a correcting entry instead';
end;
$$;

create trigger journal_entry_append_only before update or delete or truncate on journal_entry
    for each statement execute function refuse_journal_change();
create trigger journal_line_append_only before update or delete or truncate on journal_line
    for each statement execute function refuse_journal_change();
