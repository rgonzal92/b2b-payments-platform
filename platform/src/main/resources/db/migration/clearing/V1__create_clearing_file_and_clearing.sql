-- The Network's Clearing Files the platform imported, and their Clearings. Never a PAN: the import hands each record's
-- PAN to the vault and keeps only the Card Token (ADR-0004). card_token, authorization_request_id and journal_entry_id
-- point into other modules' schemas, so they carry no foreign keys (ADR-0009).
create table clearing_file (
    -- From the file's header: the Network's ID for the file, its count of files sent, the day the file covers.
    id              text primary key,
    sequence_number bigint not null,
    business_date   date not null,
    -- IMPORTING while its Clearings are posted (or an import of it stopped partway), IMPORTED once all are recorded.
    status          text not null check (status in ('IMPORTING', 'IMPORTED')),
    record_count    bigint not null,
    received_at     timestamptz not null default now(),
    imported_at     timestamptz,
    constraint clearing_file_imported_at_check check ((status = 'IMPORTED') = (imported_at is not null))
);

create table clearing (
    -- The Network's ID for the Clearing: each is recorded, and posted, once.
    network_record_id        text primary key,
    file_id                  text not null references clearing_file (id),
    -- Null when the vault knows no card with the record's PAN.
    card_token               text,
    -- DE 37 of the card's approved Authorization for the record's authorization code; null when there is none.
    authorization_request_id text,
    cleared_amount_minor     bigint not null,
    -- Null when there is no Authorization.
    authorized_amount_minor  bigint,
    currency                 char(3) not null,
    outcome                  text not null check (outcome in ('MATCHED', 'UNMATCHED')),
    -- The Journal Entry that posted it; null when it wasn't posted.
    journal_entry_id         uuid,
    recorded_at              timestamptz not null default now(),
    constraint clearing_posted_check check ((outcome = 'MATCHED') = (journal_entry_id is not null))
);

create index clearing_file_id_idx on clearing (file_id);
