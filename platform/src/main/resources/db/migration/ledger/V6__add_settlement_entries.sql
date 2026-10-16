-- A Settlement pays the Network a Clearing File's net settlement amount from Issuer Cash: debit Network Settlement
-- Payable, credit Issuer Cash. Its idempotency key is the file ID, unique among Settlements only, so it can't collide
-- with a Clearing's Network record ID.
alter table journal_entry drop constraint journal_entry_kind_check;
alter table journal_entry add constraint journal_entry_kind_check check (kind in ('CLEARING', 'SETTLEMENT'));
