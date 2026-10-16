# Dependabot

## The problem it solves here

The project pins exact versions everywhere: Spring Boot, Maven plugins, the SHAs of GitHub Actions, the Docker images in `compose.yaml`. Pins make builds reproducible, but they go stale, and stale dependencies collect known vulnerabilities. Dependabot opens pull requests that move the pins forward, and CI checks each one like any other change.

## Mental model

- **`.github/dependabot.yml` lists ecosystems to watch:** Maven (every `pom.xml`), GitHub Actions (the workflow files) and Docker Compose (`compose.yaml`).
- **Version updates run weekly and are grouped:** one pull request per ecosystem per week, instead of one per dependency.
- **Security updates are separate and immediate:** when a dependency has a published vulnerability, Dependabot opens a pull request without waiting for the schedule.
- **Its pull requests go through the same gate:** green `build` and `secrets` checks, and your review.

## Gotchas we hit

- **Some pins are invisible to it.** It can't see the PostgreSQL tag inside `TestcontainersConfiguration`, or `GITLEAKS_VERSION` and `GITLEAKS_SHA256` in the workflow. When it bumps PostgreSQL in `compose.yaml`, change the test's tag in the same pull request; bump gitleaks by hand.
- **One pin is deliberately ignored.** The OpenTelemetry Logback appender must match the OpenTelemetry API version Spring Boot manages, so Dependabot skips it; bump it by hand when Boot upgrades (see `opentelemetry.md`).
- **Actions pinned by SHA still get updates.** Dependabot rewrites the SHA and its `# vX.Y.Z` comment together.
