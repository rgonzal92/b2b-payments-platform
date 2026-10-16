-- The expiry job looks for ACTIVE cards past their expiry every minute. Only ACTIVE cards are indexed, so the index
-- stays the size of the cards still live, however many have expired or been cancelled.
create index card_expires_at_idx on card (expires_at) where status = 'ACTIVE';
