# Gatling

## The problem it solves here

ADR-0010 promises that the platform answers 99% of the Network's Authorizations within 100 ms. A promise about a p99 needs load to check it: thousands of Charges at a steady rate, the latency of each recorded, and a report that says whether anything failed. Gatling generates that load from a simulation written in Java (`load-test/src/test/java/.../AuthorizeSimulation.java`), calls the platform's `Authorize` over gRPC as the Network, and writes an HTML report of every call's response time.

## Mental model

- **Open vs closed workload.** A *closed* model keeps a fixed number of users busy: a new Charge starts only when one finishes, so a slow platform quietly lowers the rate and hides its own slowness. An *open* model starts users at a fixed arrival rate whatever the platform does, as a card Network does: Charges keep arriving while earlier ones are still waiting. The simulation is open (`injectOpen`, `rampUsersPerSec`, `constantUsersPerSec`), so 200 a second means 200 a second, and a slow answer shows as latency and a queue, not as a lower rate.
- **The Java DSL.** A simulation is a class extending `Simulation` whose constructor calls `setUp(...)`: *scenarios* (what one user does: a chain of `exec`, `randomSwitch`, `doIfOrElse`, `group`) each get an *injection profile* (how users arrive), plus *protocols* (where to send calls) and *assertions* (what makes the run fail). Here one user is one Charge.
- **gRPC.** `gatling-grpc-java` sends calls through the stubs generated from the `contracts` module's `.proto` file: `grpc("name").unary(IssuerAuthorizationGrpc.getAuthorizeMethod()).send(...)`. *Checks* decide whether a call passed: the gRPC status is `OK` and the response code is the one this kind of Charge expects (`00`, `57`, `N7`). A call whose check fails is a failed call, and the assertion `global().failedRequests().count().is(0L)` turns any into a failed build.
- **Client-side vs server-side time.** Gatling times from sending the request to reading the answer, including the network and its own scheduling. The platform's Micrometer timer (`grpc.server`, on Grafana's Authorization dashboard) times only the server's work. The 100 ms target is the server-side p99; Gatling's numbers sit beside it and should be a little higher.

## Reading the report

The console prints a summary at the end, and `load-test/target/gatling/<run>/index.html` holds the full report. Each row is a request name (`Measured: approval`, `Measured: second charge`, …) or a *group* (`Warm-up`, `Measured`), with counts of OK and KO (failed) calls and response-time percentiles: the defaults are p50, p75, p95 and p99. Read the **Measured** group for the result: the warm-up's first seconds include the JVM compiling the hot path, which is why it is reported apart. The *Response Time Percentiles over Time* chart shows whether latency drifted during the run (a queue building up) or stayed flat.

## Gotchas we hit

- **Gatling's `csv()` works only inside a running simulation.** Called from a unit test, it throws "Simulations can't be instantiated directly". The cards file has no quoting, so `Cards.read` parses it with plain Java, and its unit test runs in CI.
- **Cards come from a queue, not a Gatling feeder.** The approval chain appears in three branches and both phases, and a card handed out twice gets a `57` on its second Charge, a failed call. One shared queue of fresh cards, and one of cards the run has charged for the declines, guarantees each fresh card is charged once.
- **A channel per user is Gatling's default.** At 200 users a second that measures HTTP/2 connection set-up, not Authorizations. `shareChannel()` sends every call over one channel, as the Network keeps one connection to the Issuer.
- **Spring Boot's dependency management downgrades Gatling.** The module inherits Spring Boot's parent, which pins Netty, grpc-java and protobuf to Spring Boot's versions. The module's pom sets them back to the versions Gatling 3.16.0 was built against; update them with Gatling.
- **The load test must not run in CI.** The Gatling plugin's run is bound only in the `load-test` profile, so `./mvnw verify` compiles the simulation and runs its unit tests, and a measured run is always by hand: `-Pload-test`.
- **Fresh cards run out.** A Single-Use Card takes one approved Charge, so each run needs about 24,300 fresh cards. The simulation counts them before it starts and stops at once if the file runs dry mid-run.
