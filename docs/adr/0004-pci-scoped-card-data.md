# Treat card data as PCI-scoped even though it is synthetic

Every PAN is synthetic (Luhn-valid, from a made-up range), yet only the Card Vault stores PANs, encrypted, and every other module handles a Card Token. The aim is to practise the design PCI DSS forces on a real Issuer, keeping the PAN in as few places as possible, so the architecture would hold up with real card data.
