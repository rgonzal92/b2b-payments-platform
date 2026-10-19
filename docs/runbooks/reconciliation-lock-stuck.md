# The Reconciliation lock is stuck

**Signals:** `POST /v1/reconciliation-runs` answers `409` "Another Reconciliation is running, so the Reconciliation of … didn't start" again and again, minutes apart; at 00:05 UTC every platform pod logs `This pod skipped the daily run: Another Reconciliation is running, …` at `INFO` and no pod logs `Reconciliation run … : … Breaks found`.

How the lock works (details in [`docs/architecture.md`](../architecture.md#reconciliation), "One run at a time"): each run, scheduled or on demand, takes the session-level advisory lock `pg_try_advisory_lock(hashtext('reconciliation'))` on a pooled connection of its own and holds that connection until the run ends. It unlocks when the run ends or fails, and PostgreSQL lets the lock go when that connection's session ends. So a stuck lock means one session still holds it: a run that is still going (a slow or hung step), or a pod that is up but stuck mid-run.

Every query below runs against the development database: `docker compose exec postgres psql -U payments payments`.

## Find the holder

The session holding the lock, and how long it has held its connection:

```sql
select l.pid, a.application_name, a.client_addr, a.backend_start, a.state, a.state_change, left(a.query, 80) as last_query
  from pg_locks l
  join pg_stat_activity a on a.pid = l.pid
 where l.locktype = 'advisory' and l.granted and a.datname = 'payments';
```

The holder is usually `idle`, with `select pg_try_advisory_lock(hashtext($1))` as its last query: the run took the lock on this connection and does its work on others. `client_addr` names the pod.

Whether that run is still working: its job execution in [`reconciliation-run-failed.md`](reconciliation-run-failed.md#see-the-failed-run). A `STARTED` run whose steps' `last_updated` keeps moving is still going: wait for it. A run with no step moving for several minutes, while the lock is still held, is stuck.

## Let the lock go

1. If the holder's pod is stuck, restart it. Its connections close, and the lock goes with them.
2. If the pod can't be restarted, end only the holder's session, with the `pid` found above:

   ```sql
   select pg_terminate_backend(<pid>);
   ```

   The run on that pod logs `Couldn't release the Reconciliation lock after the run of …` when it next reaches its unlock. The rest of its work went through other connections, so that work stands. If that run is hung rather than dead, it may still finish after the next run has started. That is harmless, because two runs at once raise each Break only once.
3. Check that the lock is free: the query in "Find the holder" returns no row.
4. Run the Business Date again as Issuer staff, as in [`reconciliation-run-failed.md`](reconciliation-run-failed.md#fix-the-cause-then-run-the-date-again). The Breaks' natural keys make the re-run safe. Mark the stuck run failed so the history reads right ([`reconciliation-run-failed.md`](reconciliation-run-failed.md#the-run-is-started-but-the-platform-stopped)).
