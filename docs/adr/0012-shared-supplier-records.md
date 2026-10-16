# One Supplier per real business, shared across Buyers

A Supplier is a single record owned by the Issuer, created from the first Invoice that carries its tax ID, and each Buyer reaches it through its own Supplier Relationship. This is a deliberate exception to keeping Buyers' data apart: one record keeps a Supplier's payment details and Network identity in one place, which the Supplier acceptance milestone needs. Buyers still never see each other's Invoices or Supplier Relationships.
