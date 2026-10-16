-- Releasing a Hold keeps its row, so its record outlasts the reservation. Only PLACED Holds count against Available
-- Credit.
alter table hold add column status text not null default 'PLACED';
alter table hold add column released_at timestamptz;
alter table hold add constraint hold_released_at_check check ((status = 'RELEASED') = (released_at is not null));
