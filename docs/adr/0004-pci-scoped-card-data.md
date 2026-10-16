# Treat card data as PCI-scoped even though it is synthetic

Every PAN is synthetic (Luhn-valid, from a made-up range), yet only the Card Vault stores PANs, encrypted, and every other module handles a Card Token. The aim is to practise the design PCI DSS forces on a real Issuer, keeping the PAN in as few places as possible, so the architecture would hold up with real card data.

**Amended 2026-10-16 (#20):** the rule is that only the Card Vault stores PANs. One other module may handle a decrypted PAN: `suppliers`, in memory only, while it answers a Supplier opening its one-time Remittance Advice link, the step that hands the card's details to the Supplier the card pays. It never stores or logs the PAN or security code, and no other module receives either; only `cards` and `suppliers` may call the vault.

**Amended 2026-10-16 (M2 design):** `authorization` also handles a PAN and security code in memory only, while it answers the Network's Authorization request: it passes both straight to the vault to find the card and check the code, and never stores, logs or traces them. The modules that may call the vault become `cards`, `suppliers` and `authorization`.
