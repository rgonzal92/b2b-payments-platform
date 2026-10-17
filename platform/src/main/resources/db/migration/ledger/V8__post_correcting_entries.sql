-- A Correcting Entry moves money between two Accounts to put a Break right, as a new entry: nothing posted before is
-- changed (ADR-0015). Its idempotency key is the Break's ID, so one Break can't post two.
alter table journal_entry drop constraint journal_entry_kind_check;
alter table journal_entry add constraint journal_entry_kind_check
    check (kind in ('CLEARING', 'SETTLEMENT', 'REPAYMENT', 'CORRECTION'));
