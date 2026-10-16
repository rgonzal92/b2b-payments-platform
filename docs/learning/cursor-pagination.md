# Cursor pagination

## The problem it solves here

`GET /v1/clearing-files` lists every Clearing File the platform received, newest first, and the list only grows: one file a day or more, for years. Answering it all at once would get slower and bigger every day, so the list comes a page at a time (CLAUDE.md: "lists page by cursor"). The obvious way to page, `limit 20 offset 40`, has two faults. The database still reads and throws away the 40 rows before the page, so later pages get slower. And while someone pages, a new file arrives at the top: every row shifts down by one, so the next page repeats the last row of the page before, or, with a deletion, skips one.

## Mental model

- **A page starts after a row, not after a count.** The cursor names the last row the caller saw, by the columns the list is ordered on. The next page is "the rows that come after that one in the order": `where (received_at, id) < (:receivedAt, :id) order by received_at desc, id desc limit :limit`. This is keyset pagination; the cursor is the keyset value, wrapped.
- **The order must be total.** Two files received in the same microsecond would tie on `received_at`, and a cursor of `received_at` alone would skip one of them or show it twice. Adding the file ID, which is unique, breaks every tie, so "after this row" always means one place.
- **PostgreSQL compares row values in one go.** `(a, b) < (x, y)` means `a < x or (a = x and b < y)`, the order of a two-column index, so the index on `(received_at, id)` finds where the page starts and reads only the page's rows. Page 1,000 costs what page 1 does.
- **The cursor is opaque.** The caller gets a string and hands it back; it isn't meant to read it. Here it is the row's two values in base64url. Keeping it opaque leaves the platform free to change what's inside, and lets it refuse a string it can't read (`400`) instead of guessing. It isn't signed: a caller who builds a well-formed one just gets the page after that position, which shows nothing a first page wouldn't.
- **One extra row tells whether there is a next page.** The query asks for `limit + 1` rows; if the extra one comes back, there's another page, and `nextCursor` names the page's last row. No `count(*)`, which would read the whole table.

## How it's built here

```text
GET /v1/clearing-files?limit=2                 → items [C, B], nextCursor = base64url("<B's received_at> <B's id>")
GET /v1/clearing-files?limit=2&cursor=…        → items [A], no nextCursor
```

- `ClearingFiles.list(after, limit)` runs one of two fixed queries: the first page's, with no `where`, or the next page's, with the row comparison. `clearing` migration V5 adds the index `clearing_file_received_idx` on `(received_at, id)`.
- `ClearingFiles.Position` turns the position of the page's last file into the cursor and back; `ClearingFilesController` answers `400` for a cursor that doesn't decode to a time and an ID.
- `limit` defaults to 20 and must be 1 to 100. The OpenAPI document says so, and the generated interface checks it.

## Gotchas we hit

- **A null in JSON isn't "absent".** The platform's JSON writes a missing value as `null`, but the contract says `nextCursor` is absent on the last page. The OpenAPI document gives that one field `@JsonInclude(NON_NULL)` through the generator's `x-field-extra-annotation`, rather than changing how every other response is written. `ClearingFile.refusalReason` gets the same, so a file that wasn't refused carries no `"refusalReason": null`.
- **The generated interfaces validate through a proxy.** They carry `@Validated`, so `limit=0` fails with a `ConstraintViolationException` from the method's proxy, not with Spring MVC's own validation error, and Spring's problem details didn't cover it: it answered `500`. `ApiProblems` now turns it into a `400`.
- **A row that moves can be seen twice or not at all.** Keyset pages are stable for rows that don't change their ordering columns. A refused file whose ID arrives again, refused again or imported, takes a new `received_at` (#50), so it moves to the top of the list: someone paging at that moment may miss it, and finds it on the first page again.
