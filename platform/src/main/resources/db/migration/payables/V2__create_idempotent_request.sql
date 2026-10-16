-- Each Buyer's Idempotency-Keys. The row is claimed before the request runs and gets its response in
-- the same transaction, so a concurrent resend waits on the primary key and then replays the response.
create table idempotent_request (
    buyer_id        uuid not null,
    idempotency_key text not null,
    request_hash    bytea not null,
    response_status smallint,
    response_body   text,
    created_at      timestamptz not null default now(),
    primary key (buyer_id, idempotency_key)
);
