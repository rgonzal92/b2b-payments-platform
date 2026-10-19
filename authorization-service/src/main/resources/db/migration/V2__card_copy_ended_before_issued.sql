-- The platform may send one card's events out of order, so a CardCancelled or CardExpired can arrive before the
-- card's CardIssued. Its row is then kept with the final status and no details yet; the CardIssued fills them in
-- later and keeps the status. Only a card with every detail can be ACTIVE.
alter table card_copy
    alter column pan_hash drop not null,
    alter column security_code_hash drop not null,
    alter column expires_at drop not null,
    alter column amount_minor drop not null,
    alter column currency drop not null,
    add constraint card_copy_active_has_details check (
        status <> 'ACTIVE'
        or (pan_hash is not null and security_code_hash is not null and expires_at is not null
            and amount_minor is not null and currency is not null)
    );
