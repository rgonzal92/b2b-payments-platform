# A Clearing File arrived twice

**Signals:** `POST /v1/clearing-files` answers `409` "This file repeats Clearing File <file ID>, imported or importing, so nothing in it was posted"; the platform logs `Refused Clearing File <file name>: it repeats Clearing File <file ID>`; a file waits in the drop folder's `failed/` folder (`var/clearing/failed/` in local development).

The platform refuses a file whose file ID, or whose content hash, matches a file it imported or is importing. **Nothing in the repeat was posted.** It was moved into `processing/` as every file is, checked before its import started, and moved on to `failed/`. The content hash is the SHA-256 of the file's lines without the header's record type and file ID, so the same Clearings sent again under a new file ID match too, and without each detail record's PAN (positions 23–41), so the hash can't be worked back to a PAN. A refused file doesn't count: it wasn't imported, so the Network's corrected file may carry its ID (see [`clearing-file-refused.md`](clearing-file-refused.md)). Spring Batch's job, keyed by the file ID, refuses a completed file ID again as a second guard (see [`docs/learning/spring-batch.md`](../learning/spring-batch.md)).

One file is not a repeat: a file with both the file ID and the content hash of an import that stopped partway. Importing it restarts that import (see [`clearing-import-stopped.md`](clearing-import-stopped.md)).

Every query below runs against the development database: `docker compose exec postgres psql -U payments payments`.

1. Look at the earlier file the answer names:

   ```sql
   select id, sequence_number, business_date, status, record_count, skipped_count, content_hash,
          refusal_reason, received_at, imported_at
     from clearing.clearing_file
    where id = '<earlier file ID>';
   ```

   - `IMPORTED`: every Clearing in it is recorded.
   - `IMPORTING`: its import is running or stopped partway, and the repeat has the same file ID with other content, or the same content under another file ID. Resolve the stopped import first: [`clearing-import-stopped.md`](clearing-import-stopped.md).
2. Compare the repeat's content hash, and its sequence number and Business Date (positions 23–38 of its first line), with the earlier file's. The command works the hash out as the platform does, without the file ID and without printing or hashing a PAN:

   ```sh
   awk 'NR == 1 { print substr($0, 23); next }
        /^05/ { print substr($0, 1, 22) substr($0, 42); next } { print }' \
     var/clearing/failed/<file name> | sha256sum
   head -c 38 var/clearing/failed/<file name>; echo
   ```

   - **The same hash:** the Network sent the same Clearings again, under the same file ID or a new one. Delete the repeat (`rm var/clearing/failed/<file name>`): it holds PANs in clear text and adds nothing.
   - **Another hash, the same file ID:** the Network reused a file ID for other Clearings, which aren't posted. Keep the repeat, and ask the Network to send those Clearings in a file with a new file ID; delete the repeat once the new file is imported. Any of its records that the earlier file already brought will be skipped, not posted again.

## A repeated record is not a repeated file

A detail record whose Network record ID was already imported, in an earlier file or earlier in the same file, is skipped: nothing is posted for it, the import goes on, and the file's `skipped_count` counts it (the import's answer shows it as `skippedCount`). The Network's record IDs are unique across its files, so a count above zero means the Network sent a Clearing again; nothing on the platform needs fixing. Mention it to the Network if it keeps happening.
