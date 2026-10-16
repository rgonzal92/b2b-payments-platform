-- Payments that stopped at CARD_ISSUED before Remittance Advice delivery existed (#18) become due for it now.
update payment
   set next_attempt_at = now()
 where state = 'CARD_ISSUED'
   and next_attempt_at is null;
