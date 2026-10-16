# B2B Payments Platform

The Issuer's platform for commercial payments: Buyers receive Virtual Cards from the Issuer and use them to pay their Suppliers' Invoices.

## Language

### Parties

**Issuer**:
The bank that issues Virtual Cards to Buyers and pays for their purchases. This platform acts on its behalf.
_Avoid_: Bank, processor, issuing bank

**Issuer Staff**:
People at the Issuer who run the platform day to day: they set up Buyers and their Credit Lines and resolve Breaks.
_Avoid_: Admin, operator, back office

**Buyer**:
A company that holds Virtual Cards from the Issuer and uses them to pay Suppliers.
_Avoid_: Customer, client, cardholder, corporate

**Supplier**:
A business that sells to a Buyer and accepts card payment for what the Buyer owes.
_Avoid_: Vendor, merchant, payee

**Acquirer**:
The Supplier's bank, which accepts card payments on the Supplier's behalf and passes them to the Network.
_Avoid_: Merchant bank, acquiring bank

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
The Issuer's payment to the Network of the net total of a day's cleared Charges.
_Avoid_: Funding, payout

**Reconciliation**:
Matching each cleared Charge to its Authorization and Invoice, and flagging what doesn't match.
_Avoid_: Settlement, matching

**Break**:
A Charge, Authorization or Invoice that Reconciliation could not match, left for a person to resolve.
_Avoid_: Exception, mismatch, discrepancy

### Credit

**Credit Line**:
The most a Buyer may owe the Issuer at once.
_Avoid_: Credit limit, limit

**Available Credit**:
A Buyer's Credit Line minus cleared spend not yet repaid and minus open Holds.
_Avoid_: Open-to-buy, available balance
