-- The Acceptor ID the Supplier's Acquirer gave it (ISO 8583 DE 42), set by the first Invoice that carries one, like
-- the remittance address (ADR-0012). Null until then, and a card for a Supplier without one declines every Charge.
alter table supplier add column acceptor_id text;
