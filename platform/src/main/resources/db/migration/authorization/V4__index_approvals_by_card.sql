-- The Clearing File import finds each Clearing's Authorization by its card and authorization code (DE 38). Only an
-- approval has an authorization code, so only approvals are indexed.
create index authorizations_card_token_authorization_code_idx on authorizations (card_token, authorization_code)
    where authorization_code is not null;
