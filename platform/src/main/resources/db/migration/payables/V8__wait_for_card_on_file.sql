-- M5 (#91): a Payment waits at CARD_ISSUED, with no step due, until authorization-service has its card on file, then
-- moves to CARD_ON_FILE to deliver the Remittance Advice. A Payment at CARD_ISSUED before this was already delivering
-- (or retrying) its advice for a card the platform authorizes, so it moves to CARD_ON_FILE as it stands.
update payment set state = 'CARD_ON_FILE' where state = 'CARD_ISSUED';

-- The metric of Payments waiting for their card to be on file counts these.
create index payment_waiting_for_card_on_file_idx on payment (id) where state = 'CARD_ISSUED';
