-- Every approval and rejection of an Invoice: M1's audit trail. approver_id is the Approver's user ID at the
-- identity provider (the token's subject); approver_name is their user name then, so the record reads on its own.
create table invoice_decision (
    invoice_id    uuid not null references invoice (id),
    approver_id   text not null,
    approver_name text not null,
    decision      text not null,
    decided_at    timestamptz not null default now(),
    -- An Approver approves an Invoice at most once, and rejects it at most once.
    primary key (invoice_id, approver_id, decision)
);
