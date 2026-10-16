-- Every answer the platform gave the Network's Authorization requests. card_token points into cards' schema, so it
-- carries no foreign key (ADR-0009). Never a PAN or a security code: those stay in vault (ADR-0004).
-- The schema's name is a reserved word in PostgreSQL, so SQL outside this file spells it "authorization", quoted.
create table authorizations (
    -- DE 37, the retrieval reference number: the Network's ID for the request.
    request_id         text primary key,
    -- Null when no card has the PAN.
    card_token         text,
    -- DE 4 and DE 49.
    amount_minor       bigint not null,
    currency           char(3) not null,
    -- DE 42.
    acceptor_id        text not null,
    -- DE 39: 00 approves, anything else declines.
    response_code      text not null,
    -- DE 38: only an approval has one.
    authorization_code text,
    -- APPROVED or DECLINED.
    status             text not null,
    created_at         timestamptz not null default now()
);
