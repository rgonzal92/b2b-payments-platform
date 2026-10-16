# A Clearing File import stopped

**Signals:** `POST /v1/clearing-files` answers `500` "The import of Clearing File … stopped partway", or `409` "An import of Clearing File … is running, or stopped without recording it"; the platform logs `The import of Clearing File <file ID> stopped partway; importing it again restarts it`, usually after Spring Batch's `Rolling back chunk transaction` and `Encountered an error executing step import`; a file waits in the drop folder's `processing/` folder (`var/clearing/processing/` in local development) for more than a minute; a Clearing File stays `IMPORTING` in `clearing.clearing_file`.

How an import runs (details in [`docs/learning/spring-batch.md`](../learning/spring-batch.md)): the platform moves the file into `processing/`, checks that it doesn't repeat a file already imported, then runs the Spring Batch job `clearingFileImport` for its file ID: `validate` checks every record and the trailer's totals, `import` posts the Clearings in chunks of 100, each chunk one transaction, `settle` posts and records the file's Settlement in one transaction, and `complete` marks the file `IMPORTED`; then the file is deleted. A chunk that fails rolls back whole, and the chunks before it stay committed. **Importing the same file name again restarts the job after its last committed chunk; nothing committed is posted twice.**

Every query below runs against the development database: `docker compose exec postgres psql -U payments payments`.

## See where each import stands

The latest runs, newest first, with their file IDs:

```sql
select e.job_execution_id, p.parameter_value as file_id, e.status, e.start_time, e.end_time
  from batch.batch_job_execution e
  join batch.batch_job_execution_params p
    on p.job_execution_id = e.job_execution_id and p.parameter_name = 'fileId'
 order by e.job_execution_id desc
 limit 20;
```

A file ID with several rows was restarted; its newest row is the one that counts. For the run you're looking at, how far each step got, and why it stopped:

```sql
select step_name, status, read_count, write_count, commit_count, rollback_count, left(exit_message, 400)
  from batch.batch_step_execution
 where job_execution_id = <job_execution_id>
 order by step_execution_id;
```

`write_count` is the number of Clearings the `import` step committed; the step's saved position (`clearingRecordReader.read.count`) is in `batch.batch_step_execution_context.short_context`, as JSON. What the file has recorded so far:

```sql
select outcome, count(*) from clearing.clearing where file_id = '<file ID>' group by outcome;
```

## The run is `FAILED`: fix the cause, then import it again

1. Find the exception that rolled the chunk back: the platform's log has its stack trace under `Rolling back chunk transaction`, and the last `Caused by:` names it. The step's `exit_message` holds the same trace, cut at 2,500 characters. It names a line number of the file at most, never a field's value (ADR-0004).
   - **The database was unreachable, or a lock wait timed out:** nothing to fix in the file; go to step 2 once the database is back.
   - **Anything else that fails on every attempt** stops the file at the same chunk each time. Raise it as a bug with the stack trace; the file waits safely in `processing/` meanwhile.
2. Import the file again, under the same name, as Issuer staff. From the repository root:

   ```sh
   secret=$(grep '^SIMULATOR_ISSUER_ADMIN_SECRET=' .env | cut -d= -f2-)
   token=$(curl -s -d grant_type=client_credentials -d client_id=simulator-issuer-admin \
     --data-urlencode "client_secret=$secret" \
     http://localhost:8180/realms/payments/protocol/openid-connect/token | jq -r .access_token)
   curl -s -X POST http://localhost:8080/v1/clearing-files -H "Authorization: Bearer $token" \
     -H 'Content-Type: application/json' -d '{"fileName": "<file name>"}'
   ```

   The platform finds the file in `processing/` and restarts the job: `validate` is skipped once it completed, `import` resumes after its last committed chunk, and `settle` settles the file once, however often it ran. A `201` answer with `"status": "IMPORTED"` means every Clearing is recorded, and the file is deleted. The drop-folder check every 30 seconds doesn't restart stopped imports by itself: only a request like this one does.

## The run is `STARTED` but nothing is importing: the platform stopped mid-import

When the platform stops abruptly (killed, out of memory, the machine lost), its open chunk rolls back with the connection, but nothing records that the run ended: it stays `STARTED`, and importing the file again answers `409`, because Spring Batch can't tell a dead run from one still running elsewhere. You can.

1. Make sure no platform is importing the file: the run's newest `last_updated` in `batch.batch_step_execution` is minutes old, and no session is busy in the database for it (`select pid, state, xact_start, left(query, 80) from pg_stat_activity where datname = 'payments' and state <> 'idle';` shows no long transaction on `clearing` or `ledger`). In M3 only one platform runs; from M6, check every instance.
2. Mark the run failed, which is what Spring Batch 6's `JobOperator.recover` does:

   ```sql
   begin;
   update batch.batch_step_execution
      set status = 'FAILED', exit_code = 'FAILED', end_time = now(), version = version + 1
    where job_execution_id = <job_execution_id> and status in ('STARTING', 'STARTED', 'STOPPING');
   update batch.batch_job_execution
      set status = 'FAILED', exit_code = 'FAILED', end_time = now(), version = version + 1
    where job_execution_id = <job_execution_id>;
   commit;
   ```

3. Import the file again, as in the section above. The chunk that was open when the platform stopped rolled back, so the restart reads it again from its first record.

A run whose status is `UNKNOWN` ended with a failure Spring Batch couldn't record; a restart refuses it with "Cannot restart job from UNKNOWN status". Check what the file recorded (the outcome query above) against its chunks' `write_count`, then mark it failed with the same two updates (`status in ('STARTING', 'STARTED', 'STOPPING', 'UNKNOWN')` for the steps) and restart.

## The file is refused (`422`) or repeats another (`409`)

Neither is a stopped import: nothing in the file was posted, and it moved on to the drop folder's `failed/` folder. A file that fails a check of the `validate` step: [`clearing-file-refused.md`](clearing-file-refused.md). A file that repeats one already received: [`clearing-file-arrived-twice.md`](clearing-file-arrived-twice.md). A `validate` step that fails for another reason, such as the database being unreachable, stops the import like any other step: the file stays in `processing/`, and importing it again runs `validate` again.

## Afterwards

A file in `processing/` holds PANs in clear text until it's imported. Don't copy it anywhere, and don't leave it there once it's resolved.
