-- Issuer staff list the files newest first, a page at a time (#55): each page starts after the earlier page's last file,
-- compared on when the platform received it and then its ID, the order this index keeps.
create index clearing_file_received_idx on clearing_file (received_at, id);
