-- A Clearing File that fails a check of the import's validate step is refused whole (#50): nothing in it is posted, and
-- it is recorded REFUSED with the check it failed, by line and field, never a value.
alter table clearing_file
    drop constraint clearing_file_status_check,
    add constraint clearing_file_status_check check (status in ('IMPORTING', 'IMPORTED', 'REFUSED')),
    -- Unknown for a refused file, whose records may not all have been read.
    alter column record_count drop not null,
    add constraint clearing_file_record_count_check check (status = 'REFUSED' or record_count is not null),
    add column refusal_reason text,
    add constraint clearing_file_refusal_reason_check check ((status = 'REFUSED') = (refusal_reason is not null));
