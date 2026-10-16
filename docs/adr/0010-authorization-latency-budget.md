# Authorization answers within 100 ms; a Network timeout produces a Reversal

The platform must answer 99% of Authorizations within 100 ms. The Network waits at most 1 second, then declines the Charge and sends a Reversal, which cancels the platform's Authorization if it had approved one. This budget is the force we expect to justify extracting Authorization into its own service (ADR-0003).
