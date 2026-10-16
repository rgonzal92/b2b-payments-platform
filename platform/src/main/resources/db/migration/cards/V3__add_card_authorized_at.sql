-- When the card became AUTHORIZED: the expiry job expires an AUTHORIZED card 7 days later, as its Charge won't clear.
-- Null while the card isn't AUTHORIZED; a Reversal back to ACTIVE clears it.
alter table card add column authorized_at timestamptz;

-- A card already AUTHORIZED has no recorded time: its 7 days count from this migration, never from before its Charge.
update card set authorized_at = now() where status = 'AUTHORIZED';

-- The expiry job looks for AUTHORIZED cards past their 7 days every minute; like card_expires_at_idx, the index covers
-- only the cards it looks at.
create index card_authorized_at_idx on card (authorized_at) where status = 'AUTHORIZED';
