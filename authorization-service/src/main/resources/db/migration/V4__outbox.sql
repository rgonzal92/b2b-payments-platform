-- The service's outbox (#90): every event for the platform's authorizations topic, written in the same transaction as
-- the change it reports, and sent by the relay in id order. Each row's id comes from the identity when it's inserted,
-- after the change took its locks, so one card's events get ids in the order their transactions commit.
create table outbox (
    id         bigint      generated always as identity primary key,
    -- The message key: one card's events share a partition.
    card_token text        not null,
    -- The event-type header: AuthorizationApproved, AuthorizationReversed or CardOnFile.
    event_type text        not null,
    -- The message value, JSON as it is sent; text rather than jsonb, which would reorder its keys.
    payload    text        not null,
    created_at timestamptz not null default now(),
    -- Set once Kafka acknowledged the send; null while the row waits for the relay.
    sent_at    timestamptz
);

-- The relay reads only unsent rows, so this index stays small however many sent rows the table keeps.
create index outbox_unsent on outbox (id) where sent_at is null;
