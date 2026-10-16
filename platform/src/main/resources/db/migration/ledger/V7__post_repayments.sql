-- A Repayment's Journal Entry debits Issuer Cash and credits the Buyer Receivable. Its idempotency key is the
-- Idempotency-Key Issuer staff sent, unique among Repayments as a Clearing's Network record ID is among Clearings.
alter table journal_entry drop constraint journal_entry_kind_check;
alter table journal_entry add constraint journal_entry_kind_check
    check (kind in ('CLEARING', 'SETTLEMENT', 'REPAYMENT'));
