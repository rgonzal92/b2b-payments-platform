# A Clearing File arrived twice

**Signals:** `POST /v1/clearing-files` answers `409` "Clearing File <file ID> was already imported."; the platform logs `Clearing File <file ID> was already imported`; a file waits in the drop folder's `processing/` folder (`var/clearing/processing/` in local development) although its file ID's import completed.

The platform imports each file ID once: Spring Batch keys the import job by the file ID from the header and refuses to run a completed one again (see [`docs/learning/spring-batch.md`](../learning/spring-batch.md)). The second copy was moved into `processing/` before the refusal and left there; **nothing in it was posted.** Every query below runs against the development database: `docker compose exec postgres psql -U payments payments`.

1. Confirm the first copy's import completed, and how many Clearings it had:

   ```sql
   select id, sequence_number, business_date, status, record_count, imported_at
     from clearing.clearing_file
    where id = '<file ID>';
   ```

   `IMPORTED` means every Clearing in it is recorded. Anything else is a stopped import, not a second copy: see [`clearing-import-stopped.md`](clearing-import-stopped.md).
2. Check whether the copy holds the same Clearings. Its detail records (type `05`) and the first copy's count should agree, and so should its sequence number and Business Date (positions 23–38 of its first line):

   ```sh
   grep -c '^05' var/clearing/processing/<file name>
   head -c 38 var/clearing/processing/<file name>; echo
   ```

   - **The same:** the Network sent the file again. Delete the copy (`rm var/clearing/processing/<file name>`): it holds PANs in clear text and adds nothing.
   - **Different:** the Network reused a file ID for other Clearings, which aren't posted. Keep the copy, and ask the Network to send those Clearings in a file with a new file ID; delete the copy once the new file is imported.

#50 refuses a repeated file by its file ID or its SHA-256 before it reaches `processing/`, keeps the hash of every imported file to compare against, and moves refused files to a `failed/` folder.

A single repeated record, the same Network record ID in a later file, is a different failure: it stops that file's import partway (see [`clearing-import-stopped.md`](clearing-import-stopped.md)).
