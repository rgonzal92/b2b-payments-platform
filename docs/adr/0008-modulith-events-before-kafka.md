# In-process events with Spring Modulith; Kafka only when a module leaves the monolith

Modules react to each other through events that Spring Modulith stores in the same database transaction as the change that raised them and redelivers until handled. That gives us an outbox without running a broker. Kafka arrives with the first extracted service, the first time events must cross a process boundary; before then it would add a cluster to operate and no capability we need.
