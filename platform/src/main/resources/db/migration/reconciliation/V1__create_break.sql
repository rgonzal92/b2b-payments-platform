-- What Reconciliation could not match, left for a Reconciler (M4 design, "Kinds of Break"). record_id and buyer_id point
-- into other modules' schemas, so they carry no foreign keys (ADR-0009).
create table break (
    id            uuid primary key,
    kind          text not null check (kind in ('ABOVE_AUTHORIZED_AMOUNT', 'NO_AUTHORIZATION', 'UNKNOWN_CARD',
                                                'OTHER_CURRENCY', 'AGAINST_REVERSED_AUTHORIZATION',
                                                'AFTER_HOLD_EXPIRED', 'AUTHORIZATION_ALREADY_CLEARED',
                                                'UNDERCHARGE')),
    -- The ID of the record the Break names, which its kind says the type of: a Clearing's Network record ID.
    record_id     text not null,
    -- RESOLVED comes with the Reconciler's resolution (#71).
    status        text not null check (status in ('OPEN')),
    -- The Business Date of the run that raised it.
    business_date date not null,
    -- Null when no Buyer is known, as for a Clearing of an unknown card.
    buyer_id      uuid,
    -- How much doesn't match, never negative: what a Clearing cleared above or below its Authorization, or all of it.
    gap_minor     bigint not null check (gap_minor >= 0),
    gap_currency  char(3) not null,
    -- The named records' facts as the run found them (IDs, amounts, dates, outcome), keyed by record type.
    facts         jsonb not null,
    created_at    timestamptz not null default now(),
    -- The natural key: a run that finds this Break again raises nothing.
    constraint break_kind_record_id_key unique (kind, record_id)
);
