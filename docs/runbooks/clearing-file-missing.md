# A Clearing File is missing

**Signals:** `GET /v1/clearing-files` lists numbers under `missingSequenceNumbers`; a Business Date with Charges has no Clearing File in that list; Holds that should have cleared stay open in a Buyer's credit (`GET /v1/buyers/{id}/credit`).

The Network numbers the Clearing Files it sends the Issuer in order: the sequence number in each file's header. The list shows every number between the lowest and the highest of the platform's **imported** files that no imported file has, lowest first, the first 100 only. Files import in whatever order they arrive, so a gap can close by itself when a late file is imported. A file that is still `IMPORTING`, or was refused, doesn't fill its number: it stays missing until that file, or a corrected one, is imported. **Until the file is imported, its Clearings aren't posted:** their Holds stay open, the Buyers' Receivables and the Network Settlement Payable miss their amounts, and the file isn't settled.

## Read the list

As Issuer staff. From the repository root, in local development:

```sh
secret=$(grep '^SIMULATOR_ISSUER_ADMIN_SECRET=' .env | cut -d= -f2-)
token=$(curl -s -d grant_type=client_credentials -d client_id=simulator-issuer-admin \
  --data-urlencode "client_secret=$secret" \
  http://localhost:8180/realms/payments/protocol/openid-connect/token | jq -r .access_token)
curl -s 'http://localhost:8080/v1/clearing-files?limit=100' -H "Authorization: Bearer $token" \
  | jq '{missingSequenceNumbers, files: [.items[] | {id, sequenceNumber, businessDate, status}]}'
```

A page holds at most 100 files, newest first; pass its `nextCursor` as `cursor` for older ones. Every page carries the same `missingSequenceNumbers`.

## Find out where each missing number is

For each missing number, look for a file with that `sequenceNumber` in the list (every file the platform received is there, whatever its status):

- **`IMPORTING`:** the platform has the file, and its import is running or stopped partway: see [`clearing-import-stopped.md`](clearing-import-stopped.md). Importing it again fills the number.
- **`REFUSED`:** the file failed a check, its `refusalReason` says which, and nothing in it was posted. The Network has to send a corrected file: see [`clearing-file-refused.md`](clearing-file-refused.md).
- **None:** the platform never recorded the file. Look in the drop folder (`var/clearing/` in local development):
  - a file in the folder itself waits for the next check, every 30 seconds;
  - a name starting with `.` is a file the Network is still writing, which waits for its rename;
  - a file in `processing/` is being imported, or its import stopped before the file was recorded: see [`clearing-import-stopped.md`](clearing-import-stopped.md);
  - a file in `failed/` was refused without a readable header, so it has no file ID to record ([`clearing-file-refused.md`](clearing-file-refused.md)), or it repeats a file already imported ([`clearing-file-arrived-twice.md`](clearing-file-arrived-twice.md)). Where its first line is a header (`90`), positions 23–30 hold its sequence number: `head -c 30 var/clearing/failed/<file name>; echo`.

  Nothing there means the file never arrived.

## Ask the Network for the file

Once the Network's delivery time has passed (the simulator writes its file at 00:05 UTC) and the file is nowhere above, ask the Network, for each missing number:

1. **Whether it sent a file with that sequence number,** and if so its file ID, Business Date, record count and amount total, and when and where it was delivered.
2. **To send it again.** The same file under the same file ID is fine: the platform never imported it, so it doesn't count as a repeat. Its Clearings are posted, and settled, when it is imported; nothing else needs doing on the platform's side.
3. **If it says it sent no such file,** whether it skipped the number on purpose. The simulator's Network uses no number on a day with nothing to clear, so a quiet day leaves no gap; a real Network may send an empty file instead, or skip numbers for other reasons. A number the Network never used stays in `missingSequenceNumbers`: keep a note of it so the next person doesn't chase it again.

The gap closes once a file with the number is `IMPORTED`. Check that its Settlement is `matched`; if not, see [`settlement-mismatched.md`](settlement-mismatched.md).

## In local development: a simulator restart resets its sequence numbers

The simulator's Network keeps its sequence number in memory, so when the simulator restarts on its own while the platform keeps its database, its next file is number 1 again. The numbers then repeat (several files in the list share a sequence number), which can both:

- **hide a gap:** a file lost in a range of numbers already imported before the restart doesn't show;
- **show a gap no file ever had:** if the platform's database was emptied while the simulator kept running, its first files carry high numbers, and once the simulator restarts at 1 every number between them shows as missing.

Neither is a lost file, and there is nobody to ask. To start both sides afresh, stop both apps, run `docker compose down` (the database keeps no volume), and start them again.
