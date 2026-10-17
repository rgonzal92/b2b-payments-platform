-- When the expiry job expired the card. With authorized_at set, it expired by the 7-day rule, its Charge never cleared:
-- Reconciliation raises a never-cleared Break for each card that did so on the Business Date it reconciles, whatever
-- the card's status since (a late Clearing moves it to CLEARED). Null until the card expires.
alter table card add column expired_at timestamptz;

-- A card that expired before this migration has no recorded time: it counts as expiring now, so the next
-- Reconciliation of today finds it once, as V3 counted an AUTHORIZED card's 7 days from its own run.
update card set expired_at = now() where status = 'EXPIRED';

-- Reconciliation looks up a day's cards expired by the 7-day rule; only those are indexed.
create index card_expired_uncleared_idx on card (expired_at) where authorized_at is not null;
