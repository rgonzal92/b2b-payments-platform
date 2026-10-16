# Clearing File layout

The Network's daily batch of Clearings for the Issuer (GLOSSARY: Clearing File), modelled loosely on Visa Base II. Both sides are tested against [`sample.txt`](sample.txt) beside this file: the platform's reader (`clearing`) and the simulator's writer (the Network). Change the layout, the sample and both sides together.

## Records

- Plain text, US-ASCII, one record per line, each line ending in a line feed (`\n`).
- Every record is exactly **120 characters**; positions below count from 1.
- The first two characters are the record type, named after Base II's transaction codes: `90` the header, `05` a detail record (one Clearing), `92` the trailer.
- A file is one header, any number of detail records (none included), then one trailer, and nothing after it.
- **Alphanumeric fields** (`an`) are left-justified and padded with spaces. **Numeric fields** (`n`) are right-justified and padded with zeros, digits only, never negative. Dates are `YYYYMMDD`, by the Network's calendar in UTC. Unused positions (filler) are spaces.
- Amounts are integer minor units of their currency (cents for USD).

## Header (`90`)

| Positions | Length | Type | Field | Meaning |
|---|---|---|---|---|
| 1–2 | 2 | n | Record type | `90` |
| 3–22 | 20 | an | File ID | The Network's ID for this file, unique across all its files: letters, digits and `-`. A file whose ID was already imported is refused. |
| 23–30 | 8 | n | Sequence number | The Network's count of files sent to this Issuer, one more each file: a gap means a file is missing. |
| 31–38 | 8 | n | Business Date | The day the file covers (GLOSSARY: Business Date). |
| 39–120 | 82 | | Filler | Spaces |

## Detail record (`05`)

One per Clearing. The ISO 8583 data element (DE) is named where the field has one, as in `issuer_authorization.proto`.

| Positions | Length | Type | Field | DE | Meaning |
|---|---|---|---|---|---|
| 1–2 | 2 | n | Record type | | `05` |
| 3–22 | 20 | an | Network record ID | | The Network's ID for this Clearing, unique across all its files: letters, digits and `-`. The platform posts each Network record ID once. |
| 23–41 | 19 | an | PAN | 2 | The card's full number, 12 to 19 digits. Goes to the Issuer's vault only (ADR-0004). |
| 42–53 | 12 | n | Amount | 4 | The Charge's final amount, in minor units. |
| 54–56 | 3 | an | Currency | 49 | ISO 4217 alphabetic code, such as `USD` (real ISO 8583 carries the numeric code, as in the proto). |
| 57–68 | 12 | an | Retrieval reference number | 37 | The request ID of the Charge's Authorization. |
| 69–74 | 6 | an | Authorization code | 38 | The code the Issuer gave when it approved the Charge's Authorization. |
| 75–89 | 15 | an | Acceptor ID | 42 | The Supplier's Acceptor ID, as on the Authorization request. |
| 90–97 | 8 | n | Charge date | | The day of the Charge. |
| 98–120 | 23 | | Filler | | Spaces |

## Trailer (`92`)

The trailer's control totals are how the Issuer knows the file arrived whole.

| Positions | Length | Type | Field | Meaning |
|---|---|---|---|---|
| 1–2 | 2 | n | Record type | `92` |
| 3–10 | 8 | n | Record count | The number of detail records in the file. |
| 11–25 | 15 | n | Amount total | The sum of the detail records' amounts. |
| 26–40 | 15 | n | Net settlement amount | What the Network will settle with the Issuer for this file. |
| 41–43 | 3 | an | Currency | ISO 4217 alphabetic code of both totals. |
| 44–120 | 77 | | Filler | Spaces |

## Delivery

The Network writes each file into the Issuer's drop folder under a name starting with `.`, then renames it to its final name, which mustn't start with `.`: the platform ignores names starting with `.`, so it never reads a file that is still being written.
