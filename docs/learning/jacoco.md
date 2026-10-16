# JaCoCo

## The problem it solves here

Agents write the tests, so you need to see which code they actually exercise, not just that they pass. JaCoCo measures test coverage line by line, and SonarQube Cloud (#5) reads its report to judge new code against the quality gate.

## Mental model

- **`prepare-agent` instruments the tests.** It hands the test JVM a Java agent that records every line and branch the tests execute.
- **`report` turns the recording into reports,** including `target/site/jacoco/jacoco.xml` per module: the file SonarQube reads, and an HTML view at `target/site/jacoco/index.html` for you.
- **Configured once in the parent `pom.xml`,** so every module, present and future, is measured the same way.

## Gotchas we hit

- **The report runs in the `test` phase,** right after the tests, so `./mvnw test` already produces it; the setup matches the archived `support-ticket-insights` project.
- **Coverage shows what ran, not what was checked.** The platform's original context test "covered" startup without ever touching the database; a line counted as covered can still be unasserted.
