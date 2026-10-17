# A Reconciliation run failed

**Signals:** `POST /v1/reconciliation-runs` answers `500` "The Reconciliation run … of … didn't complete"; the platform logs `The Reconciliation run <run ID> of <Business Date> didn't complete: see docs/runbooks/reconciliation-run-failed.md`, usually after Spring Batch's `Rolling back chunk transaction` and `Encountered an error executing step clearings`; the daily run at 00:05 UTC logs no `Reconciliation run … : … Breaks found` line.

How a run works (details in [`docs/learning/reconciliation.md`](../learning/reconciliation.md)): the Spring Batch job `reconciliation` reads the Business Date's Clearings through `clearing`'s API and raises a Break for each Clearing that didn't match, 100 to a transaction. Each Break has a natural key (its kind and the record it names), so **running the same date again is always safe: it raises what the failed run didn't, and finds the rest as already known.** A failed run is never restarted; it is run again, as a new job instance with a new run ID.

Every query below runs against the development database: `docker compose exec postgres psql -U payments payments`.

## See the failed run

The latest runs, newest first, with their Business Dates:

```sql
select e.job_execution_id, p.parameter_value as business_date, e.status, e.start_time, e.end_time
  from batch.batch_job_execution e
  join batch.batch_job_instance i on i.job_instance_id = e.job_instance_id and i.job_name = 'reconciliation'
  join batch.batch_job_execution_params p
    on p.job_execution_id = e.job_execution_id and p.parameter_name = 'businessDate'
 order by e.job_execution_id desc
 limit 20;
```

Why it stopped, and how far it got:

```sql
select step_name, status, read_count, write_count, commit_count, rollback_count, left(exit_message, 400)
  from batch.batch_step_execution
 where job_execution_id = <job_execution_id>
 order by step_execution_id;
```

The Breaks raised for that date so far:

```sql
select kind, status, count(*) from reconciliation.break where business_date = '<Business Date>' group by kind, status;
```

## Fix the cause, then run the date again

1. Find the exception: the platform's log has its stack trace under `Rolling back chunk transaction`, and the step's `exit_message` holds the same trace, cut at 2,500 characters.
   - **The database was unreachable, or a lock wait timed out:** nothing to fix; go to step 2 once it's back.
   - **Anything else** fails the same way every time. Raise it as a bug with the stack trace; the date's Breaks found so far stay raised meanwhile.
2. Run the date again as Issuer staff. From the repository root:

   ```sh
   secret=$(grep '^SIMULATOR_ISSUER_ADMIN_SECRET=' .env | cut -d= -f2-)
   token=$(curl -s -d grant_type=client_credentials -d client_id=simulator-issuer-admin \
     --data-urlencode "client_secret=$secret" \
     http://localhost:8180/realms/payments/protocol/openid-connect/token | jq -r .access_token)
   curl -s -X POST http://localhost:8080/v1/reconciliation-runs -H "Authorization: Bearer $token" \
     -H 'Content-Type: application/json' -d '{"businessDate": "<Business Date>"}'
   ```

   A `201` answer means the date is reconciled: `newBreaks` are the ones the failed run didn't reach, `knownBreaks` the ones it had.

## The run is `STARTED` but the platform stopped

A run the platform didn't live to finish stays `STARTED` in the job repository. It blocks nothing: the next run is a job instance of its own. Mark it failed so the history reads right, with the two updates in [`clearing-import-stopped.md`](clearing-import-stopped.md#the-run-is-started-but-nothing-is-importing-the-platform-stopped-mid-import), then run the date again as above.

## A date missed entirely

If the platform was down at 00:05 UTC, the day before was never reconciled: the schedule doesn't catch up. Run that date by hand as above.
