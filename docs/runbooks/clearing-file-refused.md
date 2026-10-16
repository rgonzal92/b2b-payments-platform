# A Clearing File was refused

**Signals:** `POST /v1/clearing-files` answers `422` "This isn't a Clearing File the platform can import: Line <n>: …"; the platform logs `Refused Clearing File <file name>: Line <n>: …`; a file waits in the drop folder's `failed/` folder (`var/clearing/failed/` in local development); `clearing.clearing_file` has a row with status `REFUSED`.

Before anything is posted, the import's `validate` step checks the whole file: the header, every detail record and the trailer follow the layout ([`contracts/src/main/resources/clearing-file/layout.md`](../../contracts/src/main/resources/clearing-file/layout.md)), the trailer's record count and amount total are the detail records' count and sum, and the trailer's currency is USD, the only one the platform settles. A file that fails a check is refused whole: **nothing in it was posted**, it moved from `processing/` to `failed/`, and the reason names the line and the field, never what the field holds. A partial import isn't an option: the file is the Network's statement of what it will settle, and posting part of it would settle a total nobody agreed.

A `validate` step that fails for another reason, such as the database being unreachable, doesn't refuse the file: the import stops (`500`), the file stays in `processing/`, and importing it again runs `validate` again (see [`clearing-import-stopped.md`](clearing-import-stopped.md)).

Every query below runs against the development database: `docker compose exec postgres psql -U payments payments`.

## 1. See why

```sql
select id, sequence_number, business_date, refusal_reason, received_at
  from clearing.clearing_file
 where status = 'REFUSED'
 order by received_at desc;
```

A file whose first line isn't a readable header has no file ID to record, so it has no row: its reason is only in the `422` answer and the log line.

Detail records hold PANs in clear text (positions 23–41), so look at the file by field, never by whole line:

- **"the record count (positions 3–10) isn't the number of detail records"** or **"the amount total (positions 11–25) isn't the sum of the detail records' amounts"**: the file didn't arrive whole, or the Network's totals are wrong. Compare the trailer's figures with the detail records'. The amount total is a control total: the detail records' amounts summed, whatever their currency.

  ```sh
  # The trailer's record count, amount total and currency
  tail -n 1 var/clearing/failed/<file name> | cut -c 3-10,11-25,41-43 --output-delimiter=' '
  # The detail records: how many, and their amounts' sum
  grep -c '^05' var/clearing/failed/<file name>
  grep '^05' var/clearing/failed/<file name> | cut -c 42-53 | awk '{ s += $1 } END { print s }'
  ```

- **"the currency (positions 41–43) isn't USD, the only currency the platform settles"**: the Network sent the file in a currency the platform doesn't take yet (more currencies are deferred). Ask the Network for the Issuer's USD file.
- **A field or a record off the layout** ("the amount (positions 42–53) isn't a number", "the file ends without a trailer (92)", "a record follows the trailer", …): look at that line's field only, with the positions the reason gives: `sed -n '<line>p' var/clearing/failed/<file name> | cut -c <from>-<to>`. Never print positions 23–41 of a detail record.

## 2. Get a corrected file from the Network

Don't edit the file to make it pass: an edited file would settle a figure the Network never sent. Tell the Network which check failed, and ask for a corrected file. It may carry the refused file's ID or a new one: a refused file wasn't imported, so its ID isn't a repeat. A file cut short in transit, sent again whole under its own ID, imports as it should have the first time.

The corrected file is delivered (dropped) in the drop folder like any other and imported by the next check (every 30 seconds), or at once with `POST /v1/clearing-files` and its name (see [`clearing-import-stopped.md`](clearing-import-stopped.md) for the request). Its record replaces the refused one in `clearing.clearing_file`. Nothing of the refused file was posted, so every Clearing in the corrected one posts.

## 3. Delete the refused file from `failed/`

```sh
rm var/clearing/failed/<file name>
```

It holds PANs in clear text, and nothing deletes it for you: `failed/` sits under `var/`, which git ignores, but it stays on disk until a person removes it. Its reason stays recorded in `clearing.clearing_file`, so delete it as soon as you've seen why it was refused, and don't copy it anywhere first. A later file with the same name moving into `failed/` replaces it.
