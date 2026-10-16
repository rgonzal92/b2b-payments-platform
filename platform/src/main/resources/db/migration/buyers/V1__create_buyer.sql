create table buyer (
    id                       uuid primary key,
    name                     text not null,
    approval_threshold_minor bigint not null check (approval_threshold_minor >= 0),
    currency                 char(3) not null,
    created_at               timestamptz not null default now()
);
