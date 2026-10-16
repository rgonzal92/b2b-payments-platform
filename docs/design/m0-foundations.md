# M0: Foundations

**Status:** approved 2026-10-15.

## Problem

Nothing exists yet. Every later milestone needs four things in place first: a build that runs the same way for agents, for CI and on this machine; a local stack; telemetry wired up before there is anything to debug; and guardrails (formatting, secret scanning, code quality, branch protection) that apply from the first agent PR. Each costs more to retrofit once code exists.

## Done when

1. `./mvnw verify` passes from a clean clone, locally and in CI.
2. `./mvnw -pl platform spring-boot:run` starts the platform, and Spring Boot's Docker Compose support brings up PostgreSQL and `grafana/otel-lgtm` with it.
3. Grafana shows metrics, traces and logs from both `platform` and `simulator`.
4. The Spring Modulith verification test and a Testcontainers-backed context test pass for `platform`; `simulator` has its own context test.
5. Every PR runs CI (build and tests, Spotless check, gitleaks, SonarQube Cloud with its quality gate), and `main` accepts changes only through a PR with green checks.
6. The README has a "Run it locally" section that a new reader can follow.

## Approach

### Build and layout

- A parent `pom.xml` (packaging `pom`) inheriting `spring-boot-starter-parent` 4.1.1, with two modules: `platform` and `simulator`. Maven Wrapper at the root.
- Both apps are generated with Spring Initializr (Java 25, Maven) and folded under the parent, so their structure is what Spring expects.
- Base package and Maven `groupId` `dev.rgonz.payments`, matching the owner's other projects, with `.platform` and `.simulator` beneath it.
- `contracts` arrives in M1 with the first OpenAPI definition.

### platform

- Dependencies: Spring Web (MVC), Actuator, JDBC and the PostgreSQL driver, Spring Modulith (core and test), the OpenTelemetry starter, Docker Compose support (development only), Testcontainers.
- Virtual threads on (`spring.threads.virtual.enabled=true`): blocking MVC on virtual threads is the baseline that M5 measures WebFlux against.
- No business modules yet: `buyers`, `payables` and the rest appear in the milestones that need them, each with its own schema and Flyway folder (ADR-0009). Flyway arrives with the first schema in M1.
- Tests: `ApplicationModules.of(PlatformApplication.class).verify()`, plus a context test against a PostgreSQL Testcontainer wired with `@ServiceConnection`.

### simulator

- Dependencies: Spring Web, Actuator, the OpenTelemetry starter.
- An empty application in M0. It gains its ERP role in M1 and its Network role in M2.

### Local stack

`compose.yaml` at the repo root:

| Service | Image | Ports |
|---|---|---|
| PostgreSQL | `postgres:18.6` | 5432 |
| Grafana LGTM | `grafana/otel-lgtm:0.36.0` | 3000 (Grafana), 4317/4318 (OTLP) |

One database, `payments`. Module schemas are created by Flyway from M1 onwards. Keycloak joins the stack in M1, when the first login is needed.

### Telemetry

Both apps use Spring Boot's OpenTelemetry starter, exporting traces, metrics and logs over OTLP to `localhost:4318`, sampling every request locally. Tracing a request across both apps is verified in M1, with the first real call from the simulator to the platform.

### CI

`.github/workflows/ci.yml`, on every pull request and every push to `main`:

- **build:** Corretto 25 (the JDK on this machine and in `vehicle-catalog`), Maven cache, `./mvnw -B verify` (Spotless check, unit and Testcontainers tests, JaCoCo coverage), then SonarQube Cloud analysis with the Maven Sonar plugin. The analysis is skipped on pull requests from forks, which don't receive secrets.
- **secrets:** the gitleaks CLI, pinned by version and checksum, over the full history. (Changed during #4: `gitleaks/gitleaks-action` scans only the commits in a push or pull request.)

### Repository rules

- A ruleset on `main`: changes arrive only through pull requests, the `build` and `secrets` checks must pass, and force pushes are blocked.
- No required approvals: GitHub won't let you approve your own PRs, and agents open PRs under your account. Review stays your merge discipline (`CLAUDE.md`, "Pull requests").
- Dependabot opens weekly, grouped update PRs for Maven, GitHub Actions and Docker images.

### Formatting

Spotless with google-java-format. `spotless:check` runs in `verify`; `./mvnw spotless:apply` fixes formatting locally.

## Rejected options

- **Running the app against Testcontainers in development** (`spring-boot:test-run`): Compose was chosen (Q31), and it keeps Grafana and its data running across app restarts.
- **SonarQube Community Build in local Docker:** CI on GitHub's runners can't reach it, and SonarQube Cloud is free for public repositories.
- **An empty `contracts` module now:** a module with nothing in it; it arrives with its first contract.
- **A throwaway `/ping` endpoint to prove cross-app tracing:** code written to be deleted. M1's first real call proves it.
- **Required PR approvals:** impossible to satisfy alone; required status checks enforce what can be enforced.

## Risks

- **SonarQube Cloud needs one manual step from you:** sign in with GitHub, import the repository, and save a `SONAR_TOKEN` repository secret. CI's analysis step fails until then.
- **Testcontainers slows CI:** the Maven cache and one shared PostgreSQL container per test run keep it in check. If builds pass about 5 minutes, revisit.
- **Java 25 support in SonarQube Cloud's analyzer** is unconfirmed until the first run. If it lags, analysis runs with the closest supported language level.
