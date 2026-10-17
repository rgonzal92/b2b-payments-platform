-- The kinds that don't come from a Clearing (#70). Each names its own record in record_id: a never-cleared Break the
-- Authorization's request ID, an unpaid-Invoice Break the Payment's ID, a mismatched-Settlement Break its Clearing
-- File's ID (a Settlement's own), and a books Break its Business Date, check and currency, such as
-- '2026-10-15/DEBITS_AGAINST_CREDITS/USD'.
alter table break drop constraint break_kind_check;
alter table break add constraint break_kind_check
    check (kind in ('ABOVE_AUTHORIZED_AMOUNT', 'NO_AUTHORIZATION', 'UNKNOWN_CARD', 'OTHER_CURRENCY',
                    'AGAINST_REVERSED_AUTHORIZATION', 'AFTER_HOLD_EXPIRED', 'AUTHORIZATION_ALREADY_CLEARED',
                    'UNDERCHARGE', 'NEVER_CLEARED', 'UNPAID_INVOICE', 'MISMATCHED_SETTLEMENT',
                    'BOOKS_OUT_OF_BALANCE'));
