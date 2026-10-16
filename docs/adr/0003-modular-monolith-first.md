# Modular monolith first; extract a service only for a named force

We start as one Spring Boot application whose module boundaries are enforced by Spring Modulith tests. A module becomes its own service only when a specific force requires it, and each extraction gets its own ADR naming that force. Microservices from day one would mean paying for network calls, cross-service consistency and several deploy pipelines before the first feature works. Forces we already expect: Authorization's latency and availability needs, the Card Vault's PCI isolation, and Reconciliation's batch load.
