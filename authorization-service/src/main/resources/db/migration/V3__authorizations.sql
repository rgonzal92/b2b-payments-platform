-- The service answers the Network itself (#89): it moves a card between ACTIVE and AUTHORIZED, and an AUTHORIZED card,
-- like an ACTIVE one, has every detail.
alter table card_copy
    drop constraint card_copy_status_check,
    add constraint card_copy_status_check check (status in ('ACTIVE', 'AUTHORIZED', 'CANCELLED', 'EXPIRED')),
    drop constraint card_copy_active_has_details,
    add constraint card_copy_active_has_details check (
        status not in ('ACTIVE', 'AUTHORIZED')
        or (pan_hash is not null and security_code_hash is not null and expires_at is not null
            and amount_minor is not null and currency is not null)
    );

-- Every answer the service gave the Network's Authorization requests, the platform's table's final shape. Never a PAN
-- or a security code: those are hashed in memory and compared, never stored (ADR-0004, as amended by ADR-0016).
create table authorizations (
    -- DE 37, the retrieval reference number: the Network's ID for the request. Whichever insert commits first owns it:
    -- an Authorization's, or a Reversal's that arrived first.
    request_id         text        primary key,
    -- Null when no card has the PAN, and on a Reversal recorded before its Authorization.
    card_token         text,
    -- DE 4 and DE 49, the currency as the Network sent it, so a code that isn't ISO 4217 is recorded and declined.
    amount_minor       bigint,
    currency           text,
    -- DE 42.
    acceptor_id        text,
    -- DE 39: 00 approves, anything else declines; 05 on a request ID reversed before its Authorization arrived.
    response_code      text        not null,
    -- DE 38: only an approval has one, and keeps it once reversed.
    authorization_code text,
    -- APPROVED, DECLINED or REVERSED.
    status             text        not null check (status in ('APPROVED', 'DECLINED', 'REVERSED')),
    created_at         timestamptz not null default now(),
    -- A Reversal recorded before its Authorization has no card, amount, currency or Acceptor ID; every other row has
    -- all of the last three.
    constraint authorizations_charge_recorded check (
        (status = 'REVERSED' and card_token is null
            and amount_minor is null and currency is null and acceptor_id is null)
        or (amount_minor is not null and currency is not null and acceptor_id is not null))
);
