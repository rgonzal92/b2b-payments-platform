-- Every Clearing is posted and recorded with how it matched its card's Authorization (M3 design, "Matching and
-- outcomes"), instead of MATCHED or UNMATCHED. No environment kept #49's UNMATCHED Clearings: should one hold any, this
-- migration fails, as each needs a Journal Entry.
alter table clearing drop constraint clearing_outcome_check;
alter table clearing add constraint clearing_outcome_check
    check (outcome in ('MATCHED', 'ABOVE_AUTHORIZED_AMOUNT', 'NO_AUTHORIZATION', 'UNKNOWN_CARD', 'OTHER_CURRENCY',
                       'AGAINST_REVERSED_AUTHORIZATION', 'AFTER_HOLD_EXPIRED', 'AUTHORIZATION_ALREADY_CLEARED'));
alter table clearing drop constraint clearing_posted_check;
alter table clearing alter column journal_entry_id set not null;

-- An Authorization's first Clearing, found when the next one comes for it. Imports take turns on the card's row, so the
-- next one always finds it; unique besides, so an Authorization never has two first Clearings.
create unique index clearing_authorization_first_idx on clearing (authorization_request_id)
    where outcome <> 'AUTHORIZATION_ALREADY_CLEARED';
