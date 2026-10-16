# Virtual threads

## The problem it solves here

Each request to the platform spends most of its time waiting on PostgreSQL. With classic platform threads, one blocked request holds one OS thread, and the thread pool (200 by default in Tomcat) caps how many requests run at once. Virtual threads, final since Java 21, let the same blocking code serve far more concurrent requests, because a waiting virtual thread gives its OS thread back.

## Mental model

- **A virtual thread is cheap.** The JVM can run millions; each blocked one is parked in memory instead of holding an OS thread.
- **The code stays blocking and simple.** A JDBC call still reads top to bottom; there are no callbacks or reactive types.
- **One property switches it on:** `spring.threads.virtual.enabled=true` makes Tomcat run each request on a virtual thread. Request threads show up in logs as `tomcat-handler-N`.
- **It's the baseline for M5.** The extracted Authorization service will be built with WebFlux, the reactive alternative, and measured against Spring MVC on virtual threads to see whether the extra complexity pays off.

## Gotchas we hit

- **Only the platform has them on.** The simulator is a test driver, and the design keeps the setting where the measurement matters.
- **The database is still the limit.** Virtual threads remove the thread-pool cap, not the connection-pool cap: concurrent queries still queue for one of HikariCP's connections (10 by default).
