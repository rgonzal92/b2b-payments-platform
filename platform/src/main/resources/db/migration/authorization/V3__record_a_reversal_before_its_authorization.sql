-- A Reversal can arrive before the Authorization it cancels: the Network gave up while the platform was still answering.
-- The platform then records the request ID alone, as reversed, so the late Authorization finds it taken and answers 05.
-- That row has no Card Token, amount, currency or Acceptor ID; every other row still has all three of the Charge's.
alter table authorizations
    alter column amount_minor drop not null,
    alter column currency drop not null,
    alter column acceptor_id drop not null,
    add constraint authorizations_charge_recorded check (
        (status = 'REVERSED' and card_token is null)
        or (amount_minor is not null and currency is not null and acceptor_id is not null));
