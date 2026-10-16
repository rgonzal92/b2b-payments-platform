# Spring Batch

## The problem it solves here

Once a day the Network sends a Clearing File: thousands of Clearings, each of which posts to the journal, clears a Hold and moves a card to `CLEARED`. Doing that in one transaction would hold locks for minutes, and a failure on record 9,000 would roll back the 8,999 before it. Doing it one record per transaction, by hand, means writing the bookkeeping yourself: which records are done, where to resume after a crash, whether this file was already imported. Spring Batch is that bookkeeping. It splits the file into chunks, commits each chunk in its own transaction together with a note of how far it got, and on a restart resumes after the last committed chunk. It also remembers every file it ran, by the file's ID, and refuses to import a completed one again.

## Mental model

- **A job is a recipe; a job instance is one file; a job execution is one attempt.** The job `clearingFileImport` is defined once. Its *identifying parameter*, the file ID, names a job instance: one per file, for ever. Each run of that instance is a job execution. A completed instance can't run again (`JobInstanceAlreadyCompleteException`); a failed one can, and the new execution is a restart. Other parameters (here, where the file waits) are *non-identifying*: they travel with the run but don't name a new instance.
- **A job is steps in order.** `validate` checks the whole file; `import` posts its Clearings; `settle` pays the Network the file's net settlement amount; `complete` marks the file `IMPORTED`. On a restart, steps that completed are skipped, and the first one that didn't runs again.
- **A chunk-oriented step is read, (process,) write, commit.** The `import` step reads 100 Clearings one at a time from its `ItemReader`, hands the 100 to its `ItemWriter` together, and commits. Each chunk is one transaction: every posting, Hold, card move and Clearing record of those 100 commits together or not at all.
- **The job repository is the memory.** Spring Batch keeps job instances, executions, step executions and each step's *execution context* (a small map a step saves its position in) in database tables, here in the `batch` schema. At the end of every chunk, inside the chunk's transaction, the reader writes its position (`clearingRecordReader.read.count`) into the step's execution context, and the repository saves it. Because the repository shares the platform's DataSource and transaction manager, the position and the chunk's Journal Entries commit atomically: a stop can never leave Journal Entries without the position, or the position without the Journal Entries.
- **A restart resumes from the reader's position.** On restart the reader opens the file, reads the saved count, and skips that many records (`AbstractItemCountingItemStreamItemReader.jumpToItem`). The chunk that was rolled back is read again from its first record.

```text
POST /v1/clearing-files {fileName}  or  the drop-folder check, every 30 s
  └─ ClearingFileImports.importFile
       ├─ move drop/<name> → drop/processing/<name>        one atomic rename: one importer wins
       ├─ read the header: the file ID
       └─ JobOperator.start(clearingFileImport, {fileId (identifying), file (not)})
            ├─ validate   tasklet, one transaction: header, every record, trailer parse; clearing_file row IMPORTING
            ├─ import     chunks of 100, each one transaction:
            │               read 100 ─▶ for each: vault.cardFor(PAN), cards.find, authorizations.find
            │                           a known card: cards.clear, holds.isReleased, holds.clear
            │                           the outcome; journal.postClearing (or …ToSuspense)
            │                           record the Clearing with its outcome
            │               + the reader's position into the step's execution context, same commit
            ├─ settle     tasklet, one transaction: total the posted Clearings; post the trailer's figure;
            │               record the Settlement (keyed by the file ID, so a second run adds nothing)
            └─ complete   tasklet, one transaction: clearing_file row IMPORTED
       COMPLETED: delete the file │ FAILED: the file stays in processing/; importing it again restarts the job
```

## How it's built here

- **Spring Boot 4.1 with Spring Batch 6.0** (`spring-boot-starter-batch-jdbc`). Boot configures the JDBC job repository and a `JobOperator` on the platform's DataSource and transaction manager. `spring.batch.job.enabled=false` stops Boot running jobs at startup; jobs run only when a file arrives. `spring.batch.jdbc.initialize-schema=never` and `spring.batch.jdbc.table-prefix=batch.BATCH_`: the tables are created by `clearing`'s Flyway migration `db/migration/batch/V1__create_job_repository.sql`, a copy of Spring Batch's own PostgreSQL script, in the `batch` schema (ADR-0009: each module owns its schema).
- **Launching is synchronous.** Boot's `JobOperator` runs the job on the caller's thread, so `POST /v1/clearing-files` answers when the import is done. The import service is deliberately not `@Transactional`: the job repository refuses to start a job inside an open transaction, and every step must commit on its own.
- **Step-scoped reader and writer.** `@StepScope` beans are created per step execution, with `#{jobParameters['file']}` resolved at that moment, so two files importing at once never share a reader's state.
- **The step state is JSON.** An `ExecutionContextSerializer` bean (`JacksonExecutionContextStringSerializer`) replaces the default base64 Java serialization, so `batch.batch_step_execution_context.short_context` is JSON text with the reader's position under `clearingRecordReader.read.count`: the runbook can show how far a stopped import got, and the PAN check in `CorePathTests` can search inside it.
- **Proof** (`ClearingFileImportTests`): 250 Clearings, the 150th failing once through a Mockito spy on the journal. The first attempt answers `500` with 100 Clearings posted; the second answers `201` with all 250 posted exactly once. The same test fails against a reader that doesn't save its position: the restart replays the first chunk, meets Network record IDs already recorded, and stops again.

## Gotchas we hit

- **Most examples online are Spring Batch 4 or 5.** In 6.0, `JobLauncher` and `JobExplorer` folded into `JobOperator` and `JobRepository`; `JobOperator.start(job, parameters)` launches and restarts alike. `new StepBuilder(name, repository).chunk(size, transactionManager)` still compiles but returns the deprecated `SimpleStepBuilder`; `.chunk(size).transactionManager(tx)` returns the new `ChunkOrientedStepBuilder`. Packages moved too: `Job`, `JobExecution` and `JobParameters` live under `org.springframework.batch.core.job…`, and the reader and writer interfaces under `org.springframework.batch.infrastructure.item`.
- **`FlatFileItemReader` would log the PAN.** When its line mapper throws, it wraps the error in a `FlatFileParseException` whose message quotes the whole input line, and Spring Batch logs the failure and stores it in `BATCH_STEP_EXECUTION.EXIT_MESSAGE`. A detail record holds a PAN, so the import reads lines with its own small reader (`ClearingRecordReader`, on `AbstractItemCountingItemStreamItemReader`, which keeps the restart logic) and every parse error names the line and field only.
- **Everything Spring Batch stores about a failure is a stack trace.** The step's and job's `EXIT_MESSAGE` hold the exception's stack trace, messages included, so no exception the import throws may carry a field's value.
- **The default step state is unreadable.** Spring Batch 6 stores the execution context as base64 Java serialization: a text search for a PAN, or a person looking for the reader's position, sees nothing. Hence the JSON serializer above; the PAN check's own test failed until it was in place.
- **A tasklet's work commits before its step is recorded `COMPLETED`.** A tasklet step commits the tasklet's writes together with the step's interim record, then saves the `COMPLETED` status in a later transaction of its own (Spring Batch 6's `AbstractStep.execute`). A platform killed between the two leaves the step unfinished, and its restart runs the tasklet again over work already committed. So the `settle` step can run twice and settle once: its Journal Entry is keyed by the file ID, and its Settlement record by the file too.
- **A run killed without warning stays `STARTED`.** When the JVM dies mid-chunk, nothing records the end: the execution stays `STARTED`, and `start` refuses with `JobExecutionAlreadyRunningException` (answered `409`). Spring Batch can't tell a dead run from a live one on another instance; a person can. See [`runbooks/clearing-import-stopped.md`](../runbooks/clearing-import-stopped.md).
