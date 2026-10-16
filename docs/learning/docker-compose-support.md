# Spring Boot Docker Compose support

## The problem it solves here

Running the platform needs PostgreSQL and Grafana LGTM, and every app needs to know where they are. Spring Boot's Docker Compose support starts the services in `compose.yaml` when an app starts and wires the app to them, so `./mvnw -pl platform spring-boot:run` is the whole setup.

## Mental model

- **It runs `docker compose up` for you** before the application context starts, then reads the running containers.
- **It recognises well-known images.** For `postgres` it creates the database connection; for `grafana/otel-lgtm` it points the OTLP exporters at the right ports. No URLs or endpoints are configured by hand.
- **It's for development only.** The dependency is optional, Boot leaves it out of packaged jars, and it skips itself in tests, which use Testcontainers instead.

## Gotchas we hit

- **Two apps, one stack.** By default an app stops the stack when it shuts down, which would pull the database out from under the other app. Both apps set `spring.docker.compose.lifecycle-management=start-only`, and `docker compose down` stops the stack.
- **The Compose file path is relative to the module directory**, where `spring-boot:run` starts the app, hence `spring.docker.compose.file=../compose.yaml`.
- **PostgreSQL needs no password.** With `POSTGRES_HOST_AUTH_METHOD: trust`, Boot connects without one, so no credential is committed; binding the port to 127.0.0.1 keeps the database unreachable from other machines.
- **It starts the stack only when none of it is running.** With only Keycloak up (from an earlier `docker compose up keycloak`), the platform started nothing else and failed with "Failed to configure a DataSource". `docker compose up -d` brings the rest up.
- **The simulator starts PostgreSQL too,** even though it has no database: Compose support starts every service in the file.
