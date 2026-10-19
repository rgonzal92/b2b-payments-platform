# Pact

## The problem it solves here

From M5 the platform and `authorization-service` are separate processes that share no classes: the platform writes each card event as JSON onto Kafka's `cards` topic, and the service reads it with its own records (M5 design, "Events and contracts"). Nothing at compile time notices when one side renames `expiresAt`, drops `acceptorId` or changes the `event-type` header, and the break would show only at runtime, as cards the service can't read and Charges it declines. Pact makes each side's tests check the same written contract, so that drift fails a build instead.

## Mental model

- **The consumer writes the contract.** A pact is a JSON file of interactions, each one saying what one consumer needs from one provider. For Kafka these are message interactions: a description, metadata (our headers) and the content (the event's JSON), with matchers saying how strictly each field is meant (`stringType`: any string, `integerType`, `stringMatcher` with a regex). The consumer names only the fields it reads, so the provider can add others freely.
- **Consumer test:** `CardEventsPactTests` (in `authorization-service`) declares one interaction per event, and a second `CardIssued` whose `acceptorId` is `null`, with Pact's V4 DSL (`PactBuilder.expectsToReceiveMessageInteraction`), and Pact hands each test the example message it describes. The test feeds that message to the service's real `CardEventReader`, so the pact can't promise anything the service can't read. When the tests pass, Pact writes `pacts/authorization-service-platform.json`.
- **Provider test:** `CardEventsPactVerificationTests` (in the platform) is a JUnit 5 `@TestTemplate` that Pact runs once per interaction in the pact folder. For each, it calls the `@PactVerifyProvider` method named by the interaction's description, which makes the real event (issue, cancel or expire a card), reads it back from Kafka and returns its bytes and headers. Pact compares them with the pact's matchers and fails the test on any mismatch.
- **The pact files live in git, no Pact Broker.** A broker would let each side build alone and record which versions verified each other; here both sides build in one reactor, so the file in the repository is the exchange. `authorization-service` builds before the platform, so a full build verifies the pact it just wrote, and a change to the pact shows in the pull request's diff.

## Gotchas we hit

- **Pact JVM 4.7.5 is tested only up to Java 23.** We proved it on Java 25 before writing any contract (consumer and provider, a deliberately wrong header failing as it should), so Spring Cloud Contract, the fallback, wasn't needed. Spring Boot's dependency management upgrades Pact's Kotlin standard library to 2.3.21, and that works too.
- **4.7 defaults to the V4 pact format**, and a consumer method written in the older style (`MessagePact` from a `MessagePactBuilder`) fails with "does not conform required method signature 'public V4Pact xxx(PactBuilder builder)'". Use `PactBuilder` and take a `V4Interaction.AsynchronousMessage` in the test.
- **Pact writes to `target/pacts` unless told otherwise.** The service's surefire configuration sets `pact.rootDir` to the repository's `pacts` folder; the platform's test reads it with `@PactFolder("../pacts")`, relative to the module directory Maven runs in. The file is rewritten on every build with identical content, so it only changes in git when the contract does.
- **Pact JVM reports anonymous usage statistics over the network** unless the `pact_do_not_track` system property is `true`; both modules set it in surefire.
- **The provider method must run on the Spring test instance.** `MessageTestTarget` finds `@PactVerifyProvider` methods by scanning packages (we give it the test's own package) and calls those declared on the test class on the test instance itself, so the method can use the injected `Cards` and Kafka container.
- **A timestamp needs a regex, not an example.** Jackson writes an `Instant` with as many fractional digits as it has (`…:00Z`, `…:00.123456Z`), so the pact matches `expiresAt` with a regex rather than a fixed format.
