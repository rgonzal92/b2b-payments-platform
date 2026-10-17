-- A Reconciler resolves a Break (M4 design, "Resolving a Break"): accepted, reassigned or corrected, with a note, who
-- and when. correcting_entry_id points into ledger's journal, so it carries no foreign key (ADR-0009).
alter table break drop constraint break_status_check;
alter table break add constraint break_status_check check (status in ('OPEN', 'RESOLVED'));

alter table break
    add column resolution_kind     text check (resolution_kind in ('ACCEPTED', 'REASSIGNED', 'CORRECTED')),
    add column resolution_note     text,
    -- The Reconciler's user ID at the identity provider (the token's subject), and their user name for reading.
    add column resolved_by         text,
    add column resolved_by_name    text,
    add column resolved_at         timestamptz,
    -- The Correcting Entry a reassignment or correction posted; null when accepted.
    add column correcting_entry_id uuid,
    -- An open Break has no resolution; a resolved one has all of it, a Correcting Entry aside.
    add constraint break_resolution_check check (
        case status
            when 'OPEN' then num_nonnulls(resolution_kind, resolution_note, resolved_by, resolved_by_name,
                                          resolved_at, correcting_entry_id) = 0
            else num_nulls(resolution_kind, resolution_note, resolved_by, resolved_by_name, resolved_at) = 0
        end);
