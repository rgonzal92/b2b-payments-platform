-- The currency is recorded as the Network sent it, so a Charge in a code that isn't ISO 4217, or isn't three letters, is
-- recorded and declined with 61 like any other currency that isn't the card's.
alter table authorizations alter column currency type text;
