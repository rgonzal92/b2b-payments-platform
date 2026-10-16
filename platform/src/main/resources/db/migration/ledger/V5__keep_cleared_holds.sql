-- A Hold whose Charge cleared keeps its row, marked CLEARED with its clearing time: the Buyer Receivable carries the
-- cleared amount instead, so the Hold no longer counts against Available Credit.
alter table hold add column cleared_at timestamptz;
alter table hold add constraint hold_cleared_at_check check ((status = 'CLEARED') = (cleared_at is not null));
