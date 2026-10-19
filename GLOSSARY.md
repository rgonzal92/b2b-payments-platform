# B2B Payments Platform

The Issuer's platform for commercial payments: Buyers receive Virtual Cards from the Issuer and use them to pay their Suppliers' Invoices.

## Language

### Parties

**Issuer**:
The bank that issues Virtual Cards to Buyers and pays for their purchases. This platform acts on its behalf.
_Avoid_: Bank, processor, issuing bank

**Issuer Staff**:
People at the Issuer who run the platform day to day: they set up Buyers and their Credit Lines and record Repayments.
_Avoid_: Admin, operator, back office

**Reconciler**:
Issuer Staff who resolve Breaks, kept apart from those who set Credit Lines and record Repayments.
_Avoid_: Analyst, ops

**Buyer**:
A company that holds Virtual Cards from the Issuer and uses them to pay Suppliers.
_Avoid_: Customer, client, cardholder, corporate

**Supplier**:
A business that sells to a Buyer and accepts card payment for what the Buyer owes.
_Avoid_: Vendor, merchant, payee

**Acquirer**:
The Supplier's bank, which accepts card payments on the Supplier's behalf and passes them to the Network.
_Avoid_: Merchant bank, acquiring bank

**Acceptor ID**:
The identifier a Supplier's Acquirer gives it, which the Network carries on every Charge from that Supplier.
_Avoid_: Merchant ID, MID, terminal ID

**Supplier Relationship**:
A Buyer's link to a Supplier, carrying the Buyer's own identifier for that Supplier.
_Avoid_: Vendor record, supplier account

**Network**:
The card network that carries payment messages and money between Acquirers and the Issuer.
_Avoid_: Scheme, card brand, Visa

### Cards

**Virtual Card**:
A card with no physical plastic, issued by the Issuer to a Buyer for paying Suppliers.
_Avoid_: VCN, virtual account

**Single-Use Card**:
A Virtual Card issued to pay one Invoice, valid only for that Invoice's Supplier and amount.
_Avoid_: One-time card

**PAN**:
The full number of a Virtual Card.
_Avoid_: Card number, credit card number

**Card Token**:
A stand-in value that identifies a Virtual Card without revealing its PAN.
_Avoid_: Alias, masked PAN

**Card Copy**:
The authorization service's own record of a card it may authorize, kept from the platform's card events: its Card Token, the keyed hashes of its PAN and security code, expiry, amount, Acceptor ID and status.
_Avoid_: Replica, cache, card mirror

**Card on File**:
A card the authorization service now holds with its issue details in its Card Copy, so it can authorize the card's Charges. The service announces it to the platform once per card. Not the card industry's stored-credential meaning.
_Avoid_: Card stored, card ready

### Payables

**Invoice**:
A Supplier's request for payment from a Buyer, received from the Buyer's ERP.
_Avoid_: Bill, payable, payment request

**Payment**:
The Issuer's attempt to pay one approved Invoice with a Single-Use Card.
_Avoid_: Payout, transfer, transaction

**Remittance Advice**:
The note sent to a Supplier with a payment that says which Invoice it covers.
_Avoid_: Remittance, payment advice

**Approver**:
A Buyer's employee who is allowed to approve or reject Invoices.
_Avoid_: Reviewer, checker, signer

**Decision**:
An Approver's approval or rejection of an Invoice, recorded with who made it and when.
_Avoid_: Vote, sign-off

**Approval Threshold**:
The Invoice amount above which a Buyer requires two different Approvers.
_Avoid_: Approval limit

### Payment lifecycle

**Charge**:
A Supplier's attempt to take payment from a Virtual Card, carried to the Issuer by the Network.
_Avoid_: Transaction, purchase, swipe

**Authorization**:
The Issuer's real-time approve-or-decline answer to a Charge.
_Avoid_: Auth, approval

**Authorization Copy**:
The platform's copy of the approved and reversed Authorizations the authorization service answered, fed by the `authorizations` topic. Clearing matches against it, and it moves the platform's card in step. Not the Card Copy, which is the service's copy of the cards.
_Avoid_: Platform authorizations, authorization mirror

**Hold**:
The part of a Buyer's Available Credit reserved for a Virtual Card, placed when a Single-Use Card is issued or when a Charge is authorized, and kept until the Charge clears or the Hold is released.
_Avoid_: Pending amount, block, reservation

**Reversal**:
The Network's message cancelling an earlier Authorization.
_Avoid_: Void, cancellation, rollback

**Clearing**:
The Network's later message giving a Charge's final amount, which turns its Hold into posted spend.
_Avoid_: Capture, presentment, posting

**Clearing File**:
The Network's daily batch of Clearings for the Issuer.
_Avoid_: Settlement file

**Settlement**:
The Issuer's payment to the Network of the net total of one Clearing File's cleared Charges, at the Network's figure.
_Avoid_: Funding, payout

**Reconciliation**:
Matching each cleared Charge to its Authorization and Invoice, and flagging what doesn't match.
_Avoid_: Settlement, matching

**Break**:
A Charge, Authorization, Invoice or Settlement that Reconciliation could not match, left for a Reconciler to resolve.
_Avoid_: Exception, mismatch, discrepancy

**Undercharge**:
A Clearing below its Authorization's amount, leaving part of an Invoice unpaid.
_Avoid_: Short payment, partial capture

### Credit

**Credit Line**:
The most a Buyer may owe the Issuer at once.
_Avoid_: Credit limit, limit

**Available Credit**:
A Buyer's Credit Line minus its Buyer Receivable and minus open Holds.
_Avoid_: Open-to-buy, available balance

### Books

**Journal Entry**:
A balanced record of money moving between Accounts in the Issuer's books; once written, never changed or deleted.
_Avoid_: Transaction, posting row

**Account**:
A running balance in the Issuer's books that Journal Entries move money into and out of.
_Avoid_: Wallet, balance

**Buyer Receivable**:
The Account holding what one Buyer owes the Issuer for its cleared Charges.
_Avoid_: Balance due, statement balance

**Network Settlement Payable**:
The Account holding what the Issuer owes the Network for cleared Charges not yet settled.
_Avoid_: Network balance

**Issuer Cash**:
The Account for the Issuer's bank account, which Settlements are paid from and Repayments arrive in.
_Avoid_: Settlement cash, bank balance, funding account

**Repayment**:
A Buyer's payment to the Issuer that reduces its Buyer Receivable.
_Avoid_: Payment, refund, top-up

**Correcting Entry**:
A Journal Entry that fixes an earlier mistake by moving money between Accounts, leaving the original entry as it was.
_Avoid_: Adjustment, reversal, edit

**Clearing Suspense**:
The Account a Clearing is posted to when its card can't be identified, or it is in another currency than its card's, until a person resolves it.
_Avoid_: Unknown account, holding account

**Business Date**:
The day, by the Network's calendar in UTC, that a Clearing File covers.
_Avoid_: Settlement date, file date
