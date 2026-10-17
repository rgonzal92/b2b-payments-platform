-- GET /v1/breaks lists Breaks newest first, a page at a time, each page starting after the last one's (created_at, id):
-- the index keeps them in that order, read backwards, so a page never reads the Breaks before it.
create index break_created_at_id_idx on break (created_at, id);
