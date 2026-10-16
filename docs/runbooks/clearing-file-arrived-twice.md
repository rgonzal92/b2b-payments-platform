# A Clearing File arrived twice

**Signals:** `POST /v1/clearing-files` answers `409` "This file repeats Clearing File <file ID>, imported or importing, so nothing in it was posted"; the platform logs `Refused Clearing File <file name>: it repeats Clearing File <file ID>`; a file waits in the drop folder's `failed/` folder (`var/clearing/failed/` in local development).

The platform refuses a file whose file ID, or whose content hash, matches a file it imported or is importing. **Nothing in the repeat was posted.** It was moved into `processing/` as every file is, checked before its import started, and moved on to `failed/`. The content hash is the SHA-256 of the file's lines without the header's record type and file ID, so the same Clearings sent again under a new file ID match too, and without each detail record's PAN (positions 23–41), so the hash can't be worked back to a PAN. A refused file doesn't count: it wasn't imported, so the Network's corrected file may carry its ID (see [`clearing-file-refused.md`](clearing-file-refused.md)). Spring Batch's job, keyed by the file ID, refuses a completed file ID again as a second guard (see [`docs/learning/spring-batch.md`](../learning/spring-batch.md)).

One file is not a repeat: a file with both the file ID and the content hash of an import that stopped partway. Importing it restarts that import (see [`clearing-import-stopped.md`](clearing-import-stopped.md)).

One repeat isn't refused: a file the Network sends again under a new file ID **and** a new sequence number. It is imported, its records skipped and its Settlement paid again: see [A file sent again under a new file ID and a new sequence number](#a-file-sent-again-under-a-new-file-id-and-a-new-sequence-number) below.

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

A detail record whose Network record ID was already imported, in an earlier file or earlier in the same file, is skipped: nothing is posted for it, the import goes on, and the file's `skipped_count` counts it (the import's answer shows it as `skippedCount`). The Network's record IDs are unique across its files, so a count above zero means the Network sent a Clearing again. Nothing on the platform needs fixing, but check the file's Settlement: a Network that sent the record twice usually counted it twice in its net settlement amount too, and then the Settlement is mismatched by the record's amount (see [`settlement-mismatched.md`](settlement-mismatched.md)). The demo's `duplicateRecord` switch shows exactly this. Mention it to the Network if it keeps happening.

## A file sent again under a new file ID and a new sequence number

The hash leaves out the file ID, so it catches the same Clearings sent again under a new one. It keeps the sequence number and the Business Date in, so that two different files never share a hash (two empty files of one day included), and so it **doesn't** catch a file the Network sends again under a new file ID *and* a new sequence number. That file is imported like a new one:

- **Every record in it is skipped,** as each Network record ID came with the first file: nothing is posted for them, and the file's summary shows `skippedCount` equal to `recordCount` and every outcome at 0.
- **Its Settlement is posted all the same,** at its trailer's net settlement amount, because the Issuer pays what the Network says, and recorded as mismatched, as the file's posted Clearings total 0. The Issuer has now settled the same Clearings twice, and the Network Settlement Payable keeps a debit balance of that amount.
- **Its sequence number neither fills a gap nor leaves one,** so the list of files shows nothing missing.

**Signals:** the import's answer, or `GET /v1/clearing-files/<file ID>`, shows `skippedCount` equal to `recordCount` and `"settlement": {…, "matched": false}`; the platform logs `Clearing File <file ID> settled at the Network's <amount>, but its posted Clearings total 0.00 USD: see docs/runbooks/settlement-mismatched.md`.

1. Find the files whose records were all skipped, with what each settled:

   ```sql
   select f.id, f.sequence_number, f.business_date, f.record_count, f.skipped_count,
          f.amount_total_minor, s.amount_minor as settled_minor, s.imported_total_minor
     from clearing.clearing_file f
     join clearing.settlement s on s.file_id = f.id
    where f.status = 'IMPORTED' and f.record_count > 0 and f.skipped_count = f.record_count;
   ```

   A file with only some of its records skipped repeats part of an earlier file; its Settlement's gap is the repeated records' amount.
2. Find the earlier file it repeats. The repeat was deleted once imported, and its skipped records left no row of their own, so match it by its Business Date and its trailer's figures:

   ```sql
   select e.id, e.sequence_number, e.received_at, s.amount_minor as settled_minor
     from clearing.clearing_file r
     join clearing.clearing_file e
       on e.id <> r.id and e.status = 'IMPORTED' and e.business_date = r.business_date
      and e.record_count = r.record_count and e.amount_total_minor = r.amount_total_minor
     left join clearing.settlement s on s.file_id = e.id
    where r.id = '<file ID>';
   ```

   No match, or a partial repeat: the Network's answer (next step) names the earlier file.
3. Ask the Network, giving both file IDs, their sequence numbers, Business Dates and settled amounts:
   - whether file `<file ID>` carried Clearings it had already sent in the earlier file, and which Network record IDs;
   - whether it took the net settlement amount of both files, and if so to return the second file's, or to net it against the next file's Settlement;
   - why the file went out under a new file ID and a new sequence number: a repeat under its own ID, or with the same content, is refused before anything is posted, but this one is paid.
4. Leave the books as they are. The second Settlement is paid and posted, and its gap stays on the Network Settlement Payable for M4's Reconciliation to turn into a Break, as [`settlement-mismatched.md`](settlement-mismatched.md#dont-fix-the-books-by-hand) explains. Keep the Network's answer with the file ID (in the issue tracker until M4).
