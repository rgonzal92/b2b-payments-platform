-- The Single-Use Card is locked to the Invoice's Supplier, so the Payment copies it from InvoiceApproved like the Buyer
-- and amount. supplier_id points into suppliers' schema, so it carries no foreign key (ADR-0009).
alter table payment add column supplier_id uuid;

update payment
   set supplier_id = invoice.supplier_id
  from invoice
 where invoice.id = payment.invoice_id;

alter table payment alter column supplier_id set not null;

-- Payments that stopped at HOLD_PLACED before card issuance existed (#17) become due for it now.
update payment
   set next_attempt_at = now()
 where state = 'HOLD_PLACED'
   and next_attempt_at is null;
