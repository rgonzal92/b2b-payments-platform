-- The platform tells the service when a Clearing clears a card (CardCleared), so its copy can be CLEARED: final, and
-- declined like an AUTHORIZED card. It may arrive before the card's CardIssued, so it needs no details either.
alter table card_copy
    drop constraint card_copy_status_check,
    add constraint card_copy_status_check check (status in ('ACTIVE', 'AUTHORIZED', 'CANCELLED', 'EXPIRED', 'CLEARED'));
