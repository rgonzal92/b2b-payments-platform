# Dependabot

## The problem it solves here

The project pins exact versions everywhere: Spring Boot, Maven plugins, the SHAs of GitHub Actions, the Docker images in `compose.yaml`. Pins make builds reproducible, but they go stale, and stale dependencies collect known vulnerabilities. Dependabot opens pull requests that move the pins forward, and CI checks each one like any other change.

## Mental model

- **`.github/dependabot.yml` lists ecosystems to watch:** Maven (every `pom.xml`), GitHub Actions (the workflow files) and Docker Compose (`compose.yaml`).
- **Version updates run weekly and are grouped:** one pull request per ecosystem per week, instead of one per dependency.
- **Security updates are a separate switch, and it's off here.** With Dependabot alerts and security updates enabled in the repository settings, a published vulnerability gets its own pull request straight away; today only the weekly version updates run.
- **Its pull requests go through the same gate:** green `build` and `secrets` checks, and your review.

## Gotchas we hit

- **Some pins are invisible to it.** It can't see the PostgreSQL tag inside `TestcontainersConfiguration`, the google-java-format version inside the Spotless configuration, or the gitleaks version in the workflow. When it bumps PostgreSQL in `compose.yaml`, change the test's tag in the same pull request; bump google-java-format by hand, and gitleaks as `gitleaks.md` describes.
- **One pin is deliberately ignored.** The OpenTelemetry Logback appender must match the OpenTelemetry API version Spring Boot manages, so Dependabot skips it, including any future security updates; bump it by hand when Boot upgrades (see `opentelemetry.md`).
- **Actions pinned by SHA still get updates.** Dependabot rewrites the SHA and its `# vX.Y.Z` comment together.
