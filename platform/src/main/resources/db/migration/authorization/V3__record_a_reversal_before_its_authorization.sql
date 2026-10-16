-- A Reversal can arrive before the Authorization it cancels: the Network gave up while the platform was still answering.
-- The platform then records the request ID alone, as reversed, so the late Authorization finds it taken and answers 05.
-- That row, a REVERSED one without a Card Token, has no amount, currency or Acceptor ID; every other row has all three.
alter table authorizations
    alter column amount_minor drop not null,
    alter column currency drop not null,
    alter column acceptor_id drop not null,
    add constraint authorizations_charge_recorded check (
        (status = 'REVERSED' and card_token is null
            and amount_minor is null and currency is null and acceptor_id is null)
        or (amount_minor is not null and currency is not null and acceptor_id is not null));
