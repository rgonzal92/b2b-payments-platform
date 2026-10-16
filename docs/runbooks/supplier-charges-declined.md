# A Supplier's Charges are declined `57`

**Signals:** every `Authorize` on one Supplier's cards answers `57` (not permitted); the platform logs `Authorization … answered 57`; rows in `"authorization".authorizations` for those cards have `response_code = '57'` and a `card_token`.

Authorization approves a Charge only when its Acceptor ID (DE 42) is exactly the one stored for the card's Supplier, and a card whose Supplier has no Acceptor ID can't be charged at all (see [The Network link](../architecture.md#the-network-link)). The Supplier's Acceptor ID comes from the Buyer's ERP: the first Invoice that carries one sets it, and a different one on a later Invoice is ignored. So either no Invoice has given the Supplier one, or the first one it got isn't what its Acquirer sends. A decline changes nothing: the card stays `ACTIVE` with its Hold until it expires, so the Supplier can charge it again once the Acceptor ID is right.

1. Compare what the Charges carry with what the Supplier holds. This operator query joins three modules' schemas, which the platform's own code never does (ADR-0009); it works while they share one database, and needs splitting when `authorization` becomes its own service (M5).

   ```sql
   select a.acceptor_id as charged_with, s.acceptor_id as supplier_has, s.tax_id, s.name, count(*)
     from "authorization".authorizations a
     join cards.card c on c.card_token = a.card_token
     join suppliers.supplier s on s.id = c.supplier_id
    where a.response_code = '57'
      and a.created_at > now() - interval '1 day'
    group by 1, 2, 3, 4;
   ```

2. **The Supplier has none** (`supplier_has` is null): the Buyer's ERP doesn't send `acceptorId` on its Invoices. Have it send the Supplier's Acceptor ID; the next Invoice naming the Supplier sets it, and Charges on cards already issued are approved from then on. Locally, the simulator's ERP sends `simulator.globex.acceptor-id` (in `simulator/src/main/resources/application.properties`) on every Invoice, so one demo Invoice fixes Globex.
3. **The Supplier has a different one:** the first ERP to name an Acceptor ID for the Supplier sent the wrong one, or the Charges come from somewhere else. Check with the Supplier which Acceptor ID its Acquirer gave it before changing anything: a Charge with another Acceptor ID may be someone other than the Supplier trying to charge its card, which is the decline doing its job. There is no way yet to correct it through the API (Supplier verification belongs to the Supplier acceptance milestone); in local development, `update suppliers.supplier set acceptor_id = … where id = …` takes effect at the next Charge.
