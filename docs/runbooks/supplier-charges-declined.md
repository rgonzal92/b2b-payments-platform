# A Supplier's Charges are declined `57`

**Signals:** every `Authorize` on one Supplier's cards answers `57` (not permitted); `authorization-service` logs `Authorization … answered 57`; rows in the service's `authorizations` table (its own PostgreSQL, `authorization-postgres`) for those cards have `response_code = '57'` and a `card_token`. Declines live only there: the service publishes approved and reversed Authorizations, so the platform's Authorization Copy never sees a `57`. A `57` also answers a second Charge on a card that already has an approved Authorization; in the queries below those rows show the Acceptor ID the Supplier holds, and aren't this failure.

Authorization approves a Charge only when its Acceptor ID (DE 42) is exactly the one stored for the card's Supplier, and a card whose Supplier has no Acceptor ID can't be charged at all (see [The Network link](../architecture.md#the-network-link)). The Supplier's Acceptor ID comes from the Buyer's ERP: the first Invoice that carries one sets it, and a different one on a later Invoice is ignored. So either no Invoice has given the Supplier one, or the first one it got isn't what its Acquirer sends. A decline changes nothing: the card stays `ACTIVE` with its Hold until it expires, so the Supplier can charge it again once the Acceptor ID is right.

1. Compare what the Charges carry with what the Supplier holds. Since the cutover (#92) the declines are in the service's database and the Suppliers in the platform's, so this takes two queries. First, the Acceptor IDs and cards of the recent `57`s, in the service's database:

   ```sh
   docker compose exec authorization-postgres psql -U authorization -d authorization -c "
     select acceptor_id as charged_with, card_token, count(*)
       from authorizations
      where response_code = '57'
        and created_at > now() - interval '1 day'
      group by 1, 2;"
   ```

   Then, in the platform's database, what each of those cards' Suppliers holds (this operator query joins two modules' schemas, which the platform's own code never does, ADR-0009):

   ```sql
   select c.card_token, s.acceptor_id as supplier_has, s.tax_id, s.name
     from cards.card c
     join suppliers.supplier s on s.id = c.supplier_id
    where c.card_token in (…);   -- the card_token values from the first query
   ```

2. **The Supplier has none** (`supplier_has` is null): the Buyer's ERP doesn't send `acceptorId` on its Invoices. Have it send the Supplier's Acceptor ID; the next Invoice naming the Supplier sets it, and Charges on cards already issued are approved from then on. Locally, the simulator's ERP sends `simulator.globex.acceptor-id` (in `simulator/src/main/resources/application.properties`) on every Invoice, so one demo Invoice fixes Globex.
3. **The Supplier has a different one:** the first ERP to name an Acceptor ID for the Supplier sent the wrong one, or the Charges come from somewhere else. Check with the Supplier which Acceptor ID its Acquirer gave it before changing anything: a Charge with another Acceptor ID may be someone other than the Supplier trying to charge its card, which is the decline doing its job. There is no way yet to correct it through the API (Supplier verification belongs to the Supplier acceptance milestone); in local development, `update suppliers.supplier set acceptor_id = … where id = …` takes effect at the next Charge.
