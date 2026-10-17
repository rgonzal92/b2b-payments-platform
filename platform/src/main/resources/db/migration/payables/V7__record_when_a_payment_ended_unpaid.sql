-- When the Payment ended FAILED or EXPIRED, leaving its Invoice unpaid: Reconciliation raises an unpaid-Invoice Break
-- for each Payment that ended on the Business Date it reconciles (M4 design, "Kinds of Break"). Null in every other
-- state.
alter table payment add column ended_at timestamptz;

-- A Payment that ended before this migration has no recorded time: it counts as ending now, so the next Reconciliation
-- of today finds it once, never a day long reconciled.
update payment set ended_at = now() where state in ('FAILED', 'EXPIRED');

-- Reconciliation looks up a day's ended Payments; only those are indexed.
create index payment_ended_at_idx on payment (ended_at) where ended_at is not null;
